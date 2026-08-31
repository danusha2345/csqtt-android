#!/usr/bin/env python3
"""Write or verify cross-platform provenance for the Android Rust client."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "app/src/main/libclient.provenance.json"
CARGO_TOML = ROOT / "rust-client/Cargo.toml"
CARGO_LOCK = ROOT / "rust-client/Cargo.lock"
MARKER = "CSQTT_RUST_NATIVE_PRODUCTION_V1"
SCHEMA = "csqtt.native-client-provenance.v3"
PRODUCER = "cross-platform-native-build"
TRANSPORT_MARKER = b"CSQTT_RUST_TURN_SANS_IO_V1"
SPECS = (
    {
        "abi": "arm64-v8a",
        "target": "aarch64-linux-android",
        "path": "app/src/main/jniLibs/arm64-v8a/libclient.so",
        "cargo": "build/rust-client-android-arm64/aarch64-linux-android/release/client",
        "elf_class": 2,
        "machine": 183,
    },
    {
        "abi": "armeabi-v7a",
        "target": "armv7-linux-androideabi",
        "path": "app/src/main/jniLibs/armeabi-v7a/libclient.so",
        "cargo": "build/rust-client-android-armv7/armv7-linux-androideabi/release/client",
        "elf_class": 1,
        "machine": 40,
    },
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def require_file(path: Path, label: str) -> None:
    if not path.is_file() or path.stat().st_size <= 0:
        raise RuntimeError(f"{label} not found or empty: {path}")


def package_identity() -> tuple[str, str]:
    text = CARGO_TOML.read_text(encoding="utf-8")
    package = text.split("[package]", 1)[1].split("[", 1)[0]
    name = re.search(r'^name\s*=\s*"([^"]+)"', package, re.MULTILINE)
    version = re.search(r'^version\s*=\s*"([^"]+)"', package, re.MULTILINE)
    if not name or not version:
        raise RuntimeError("Cannot parse rust-client package identity")
    return name.group(1), version.group(1)


def input_files() -> list[Path]:
    files = [
        ROOT / "rust-client/build_so.bat",
        ROOT / "rust-client/build_android_abis.ps1",
        CARGO_TOML,
        CARGO_LOCK,
        ROOT / "scripts/find_android_sdk_ndk.bat",
        ROOT / "scripts/build_android_native.sh",
        ROOT / "scripts/native_client_provenance.py",
        ROOT / "scripts/native_client_provenance.ps1",
        ROOT / "shared/striped_scheduler.rs",
    ]
    files.extend((ROOT / "rust-client").glob("*.rs"))
    files.extend(path for path in (ROOT / "rust-client/dispatcher").rglob("*") if path.is_file())
    files.extend(path for path in (ROOT / "rust-client/vendor/primp").rglob("*") if path.is_file())
    unique = sorted({path.resolve() for path in files})
    for path in unique:
        require_file(path, "Native build input")
    return unique


def build_input_hash() -> str:
    lines = []
    for path in input_files():
        relative = path.relative_to(ROOT.resolve()).as_posix()
        lines.append(f"{relative}:{sha256(path)}\n")
    return hashlib.sha256("".join(lines).encode("utf-8")).hexdigest().upper()


def verify_elf(path: Path, spec: dict[str, object]) -> None:
    require_file(path, f"{spec['abi']} artifact")
    data = path.read_bytes()
    if data[:4] != b"\x7fELF" or data[4] != spec["elf_class"] or data[5] != 1:
        raise RuntimeError(f"Invalid ELF class/endianness for {spec['abi']}: {path}")
    elf_type = int.from_bytes(data[16:18], "little")
    machine = int.from_bytes(data[18:20], "little")
    if elf_type != 3 or machine != spec["machine"]:
        raise RuntimeError(f"Invalid ELF type/machine for {spec['abi']}: {path}")
    if TRANSPORT_MARKER not in data:
        raise RuntimeError(f"Missing TURN production marker for {spec['abi']}: {path}")


def artifact_entries(require_cargo_match: bool = True) -> list[dict[str, object]]:
    entries = []
    for spec in SPECS:
        production = ROOT / str(spec["path"])
        cargo = ROOT / str(spec["cargo"])
        verify_elf(production, spec)
        if require_cargo_match:
            verify_elf(cargo, spec)
            if sha256(production) != sha256(cargo):
                raise RuntimeError(f"Production artifact does not match Cargo output for {spec['abi']}")
        entries.append(
            {
                "abi": spec["abi"],
                "target": spec["target"],
                "path": spec["path"],
                "size": production.stat().st_size,
                "sha256": sha256(production),
            }
        )
    return entries


def write_manifest() -> None:
    require_file(CARGO_LOCK, "Cargo.lock")
    name, version = package_identity()
    manifest = {
        "schema": SCHEMA,
        "marker": MARKER,
        "producer": PRODUCER,
        "runtime": "rust",
        "buildSystem": "cargo-ndk",
        "profile": "release",
        "transport": "rust-turn-sans-io-rfc8656-channeldata",
        "transportMarker": TRANSPORT_MARKER.decode("ascii"),
        "nativeCoreAbi": 1,
        "package": name,
        "version": version,
        "buildInputSha256": build_input_hash(),
        "cargoTomlSha256": sha256(CARGO_TOML),
        "cargoLockSha256": sha256(CARGO_LOCK),
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "artifacts": artifact_entries(),
    }
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote Rust native provenance: {MANIFEST}")


def verify_manifest() -> None:
    require_file(MANIFEST, "Rust native provenance")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    name, version = package_identity()
    expected = {
        "schema": SCHEMA,
        "marker": MARKER,
        "producer": PRODUCER,
        "runtime": "rust",
        "buildSystem": "cargo-ndk",
        "profile": "release",
        "transport": "rust-turn-sans-io-rfc8656-channeldata",
        "transportMarker": TRANSPORT_MARKER.decode("ascii"),
        "nativeCoreAbi": 1,
        "package": name,
        "version": version,
        "buildInputSha256": build_input_hash(),
        "cargoTomlSha256": sha256(CARGO_TOML),
        "cargoLockSha256": sha256(CARGO_LOCK),
        "artifacts": artifact_entries(),
    }
    for key, value in expected.items():
        actual = manifest.get(key)
        if key == "artifacts":
            actual = [{**item, "sha256": item["sha256"].upper()} for item in actual or []]
        if actual != value:
            raise RuntimeError(f"Native provenance mismatch: {key}")
    print(f"Rust native provenance verified: {MARKER}")


def verify_release() -> None:
    verify_manifest()
    _, version = package_identity()
    server = ROOT / "app/src/main/assets/csqtt"
    deploy = ROOT / "app/src/main/assets/deploy.sh"
    require_file(server, "Embedded server")
    require_file(deploy, "deploy.sh")
    if f"CSQTT Server {version}".encode("utf-8") not in server.read_bytes():
        raise RuntimeError(f"Embedded server does not report version {version}")
    deploy_text = deploy.read_text(encoding="utf-8")
    required = (
        f'SCRIPT_VERSION="{version}"',
        "CSQTT_DEPLOY_READY_FOR_UPLOAD",
        "prepare_uploaded_release",
    )
    for marker in required:
        if marker not in deploy_text:
            raise RuntimeError(f"deploy.sh release marker missing: {marker}")
    print(f"Native release inputs verified for CSQTT {version}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("write", "verify", "verify-release"))
    args = parser.parse_args()
    if args.mode == "write":
        write_manifest()
    elif args.mode == "verify-release":
        verify_release()
    else:
        verify_manifest()


if __name__ == "__main__":
    main()
