#!/usr/bin/env python3
"""Проверка встроенных серверов по архитектуре, версии и исходникам сборки."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from datetime import datetime, timezone
from pathlib import Path


SPECS = (
    ("csqtt-linux-amd64", "x86_64-unknown-linux-musl", 2, 62),
    ("csqtt-linux-arm64", "aarch64-unknown-linux-musl", 2, 183),
    ("csqtt-linux-armv7", "armv7-unknown-linux-musleabihf", 1, 40),
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def identity(root: Path) -> tuple[str, str]:
    cargo = (root / "rust-server/Cargo.toml").read_text()
    version = re.search(r'^version\s*=\s*"([^"]+)"', cargo, re.MULTILINE)
    wire = re.search(
        r'WIRE_PROTOCOL_REVISION\s*:\s*&str\s*=\s*"([^"]+)"',
        (root / "shared/wire_protocol.rs").read_text(),
    )
    if version is None or wire is None:
        raise RuntimeError("Не найдены версия сервера или идентификатор протокола")
    return version[1], wire[1]


def build_hash(root: Path) -> str:
    files = list((root / "rust-server").glob("*.rs"))
    files += list((root / "shared").glob("*.rs"))
    files += [root / "rust-server/Cargo.toml", root / "rust-server/Cargo.lock", root / "scripts/server_asset_provenance.py"]
    payload = "".join(
        f"{path.relative_to(root).as_posix()}:{digest(path)}\n" for path in sorted(files)
    )
    return hashlib.sha256(payload.encode()).hexdigest().upper()


def check_elf(data: bytes, elf_class: int, machine: int) -> None:
    if len(data) < 64 or data[:6] != b"\x7fELF" + bytes((elf_class, 1)):
        raise RuntimeError("Некорректный ELF или разрядность серверного бинарника")
    if struct.unpack_from("<H", data, 18)[0] != machine:
        raise RuntimeError("Неверная архитектура серверного бинарника")
    if elf_class == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
    if size < 4 or offset + size * count > len(data):
        raise RuntimeError("Повреждена таблица сегментов ELF")
    for index in range(count):
        if struct.unpack_from("<I", data, offset + size * index)[0] == 3:
            raise RuntimeError("Сервер зависит от динамического загрузчика; нужна статическая musl-сборка")


def expected(root: Path) -> dict:
    version, wire = identity(root)
    artifacts = []
    for name, target, elf_class, machine in SPECS:
        asset = root / "app/src/main/assets" / name
        data = asset.read_bytes()
        check_elf(data, elf_class, machine)
        if f"CSQTT Server {version}".encode() not in data or wire.encode() not in data:
            raise RuntimeError(f"{name}: версия или wire protocol не соответствуют исходникам")
        artifacts.append({
            "assetName": name, "target": target, "elfClass": elf_class,
            "elfMachine": machine, "binarySize": len(data), "binarySha256": digest(asset),
        })
    return {
        "schema": "csqtt.server-asset-provenance.v2", "runtime": "rust",
        "package": "csqtt", "version": version, "wireProtocolRevision": wire,
        "buildInputSha256": build_hash(root),
        "cargoTomlSha256": digest(root / "rust-server/Cargo.toml"),
        "cargoLockSha256": digest(root / "rust-server/Cargo.lock"), "artifacts": artifacts,
    }


def verify(root: Path) -> None:
    recorded = json.loads((root / "app/src/main/assets/csqtt.server-provenance.json").read_text())
    for key, value in expected(root).items():
        if recorded.get(key) != value:
            raise RuntimeError(f"Серверный provenance не совпадает: {key}")
    print("Проверены серверные assets amd64/arm64/armv7, версия, протокол и исходники")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("write", "verify"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.mode == "write":
        data = expected(root)
        data["generatedAtUtc"] = datetime.now(timezone.utc).isoformat()
        (root / "app/src/main/assets/csqtt.server-provenance.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n"
        )
    else:
        verify(root)


if __name__ == "__main__":
    main()
