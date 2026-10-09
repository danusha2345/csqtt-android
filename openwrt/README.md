# CSQTT для OpenWrt

Ветка добавляет нативный Linux TUN в `csqtt-client` и пакет OpenWrt с
`procd`-сервисом. Первый порт рассчитан на современные роутеры `x86_64`,
`aarch64` и `armv7`. MIPS/MIPSel пока не заявлены: их нужно отдельно проверять
с OpenWrt SDK и собирать стандартную библиотеку Rust для конкретного target.

OpenWrt 25.12 и новее используют `apk`, OpenWrt 24.10 и старее — `opkg`.
Каталог содержит feed-рецепт `Makefile`, поэтому окончательный пакет следует
создавать SDK именно той версии OpenWrt, которая установлена на роутере.

Текущая ревизия пакетов: **2.1.16-2**, тег `openwrt-v2.1.16-r2`.
В ней добавлен выбор `nftables`/`iptables`; версия Rust-клиента остаётся 2.1.16.
Готовые файлы: [OpenWrt 2.1.16-2](https://github.com/danusha2345/csqtt-android/releases/tag/openwrt-v2.1.16-r2).

## Сборка пакетов

Нужны Rust 1.97.1, Zig и `cargo-zigbuild`:

```bash
cargo install cargo-zigbuild
scripts/build_openwrt.sh
```

Скрипт создаёт musl-бинарники и legacy `.ipk` для OpenWrt 24.10 в
`target/openwrt-packages/`. Можно собрать один target, передав его явно:

```bash
scripts/build_openwrt.sh aarch64-unknown-linux-musl
```

Для текущего OpenWrt 25.12 подключите `openwrt/` как локальный package feed к
совпадающему OpenWrt SDK и передайте уже собранный musl-бинарник:

```bash
ln -s /path/to/csqtt-android/openwrt /path/to/openwrt-sdk/package/csqtt-client
make -C /path/to/openwrt-sdk package/csqtt-client/compile V=s \
  CSQTT_BINARY=/path/to/csqtt-android/rust-client/target/aarch64-unknown-linux-musl/release/client
```

SDK 25.12 сформирует `.apk`, SDK 24.10 — `.ipk`, с корректной архитектурой и
метаданными своего release. Ручной `package.sh` предназначен только для
быстрой `.ipk`-упаковки под 24.10.

## Выбор файла из GitHub Release

Узнайте архитектуру роутера:

```sh
uname -m
```

- `x86_64` → файл с `x86_64`;
- `aarch64` → файл с `aarch64_generic`;
- `armv7l` и наличие `neon` в `/proc/cpuinfo` → файл с
  `arm_cortex-a7_neon-vfpv4`;
- MIPS/MIPSel пока не поддерживаются — не устанавливайте ARM-файл на такой
  роутер.

Архив `.tar.gz` подходит для ручной установки на OpenWrt 24.10 и 25.12+.
Файл `.ipk` предназначен только для OpenWrt 24.10 с `opkg`.

## Установка готового архива на OpenWrt 25.12+

Скопируйте подходящий `.tar.gz` на роутер, например в `/tmp`, затем:

```sh
apk add kmod-tun ip-full
mkdir -p /tmp/csqtt-install
tar -xzf /tmp/csqtt-openwrt_2.1.16-2_aarch64_generic.tar.gz \
  -C /tmp/csqtt-install
sh /tmp/csqtt-install/install.sh
```

Для OpenWrt 24.10 можно либо установить аналогичный архив вручную, либо пакет:

```sh
opkg update
opkg install kmod-tun ip-full
opkg install /tmp/csqtt-client_2.1.16-2_aarch64_generic.ipk
```

### Выбор firewall

При `route_lan='1'` hook автоматически выбирает firewall:

- активная таблица `inet fw4` → нативные правила `nftables` в цепочках
  `forward` и `srcnat`, даже если одновременно установлен `iptables`;
- без `fw4` → прежние правила `iptables` в `FORWARD` и `POSTROUTING`.

На стандартных образах с `firewall4` нужны работающий сервис firewall и команда
`nft`, которые обычно уже установлены. `iptables-nft` для этого варианта больше
не нужен. Если `fw4` установлен, но его таблица недоступна, hook сообщает об
ошибке и просит сначала запустить firewall; переключения на `iptables` нет.
Для старых или нестандартных образов с `firewall3` сохраните установленный
`iptables` с поддержкой `comment`, `conntrack` и `MASQUERADE`.
Пакет CSQTT не устанавливает и не заменяет firewall роутера.

Выбор определяется фактическим firewall, а не номером OpenWrt:
[firewall4 используется по умолчанию начиная с 22.03](https://openwrt.org/docs/guide-user/firewall/firewall_configuration).
Для SOCKS5 и TUN с `route_lan='0'` firewall-команды не нужны.

Правила относятся только к IPv4, как и текущий TUN. Повторный `up` удаляет
собственные старые правила, а ошибка настройки откатывает policy route и
частично добавленные правила. Перезапуск firewall удаляет динамические правила
CSQTT: после него выполните `/etc/init.d/csqtt restart`.

## Настройка

```sh
uci set csqtt.main.enabled='1'
uci set csqtt.main.peer='vpn.example.org:46010'
uci set csqtt.main.password='connection-password'
uci set csqtt.main.vk_hashes='hash1,hash2'
uci commit csqtt
/etc/init.d/csqtt enable
/etc/init.d/csqtt restart
logread -e csqtt
```

Замените `vpn.example.org:46010`, пароль и VK-хеши своими значениями. Настройки
хранятся в `/etc/config/csqtt` с правами `0600`. Для нескольких хешей используйте
одну строку через запятую.

Секреты из UCI перед стартом переносятся в файл `0600` под `/var/run` и не
появляются в командной строке процесса.

### Режимы

- `mode='tun'` создаёт `csqtt0`. При `route_lan='1'` трафик, пришедший с
  `lan_device` (по умолчанию `br-lan`), направляется в отдельную таблицу 202;
  локальный трафик самого роутера остаётся на обычном WAN и не зацикливает TURN.
- `mode='socks5'` поднимает прокси на `socks5_listen` без изменения маршрутов.
  По умолчанию он слушает только `127.0.0.1:1080`; менять адрес на LAN следует
  только вместе с отдельными firewall-ограничениями.

`csqtt-tun` применяет адрес из `TUNCONF`, добавляет policy route, forwarding и
MASQUERADE, а при остановке удаляет только созданные им правила. DNS из
`TUNCONF` пока лишь выводится в `logread`: DHCP/DNS-конфигурация LAN намеренно
не переписывается автоматически.

## Проверка на роутере

```sh
ip link show csqtt0
ip rule show | grep 12000
ip route show table 202
# nftables / firewall4:
nft -a list chain inet fw4 forward | grep csqtt-openwrt
nft -a list chain inet fw4 srcnat | grep csqtt-openwrt
# Или iptables / firewall3:
iptables -S FORWARD | grep csqtt0
iptables -t nat -S POSTROUTING | grep csqtt0
```

Полная готовность конкретной модели подтверждается только тестом на реальном
OpenWrt-устройстве: старт после reboot, доступ LAN через туннель, отсутствие
утечки пароля в `ps`, восстановление после обрыва и корректная остановка.

Локальные регрессии hook запускаются на Linux с `nft`, `iptables`, `ip`,
BusyBox и поддержкой user/network namespaces:

```sh
python3 scripts/test_openwrt_firewall.py --require-netns
shellcheck openwrt/files/usr/libexec/csqtt-tun
```

Тесты применяют настоящие правила в отдельном network namespace и не меняют
сеть хоста. Проверяется также BusyBox `sh`; это не заменяет прогон на роутере.

## Помогите протестировать beta

Нужны результаты с реальных роутеров `x86_64`, `aarch64` и `armv7` с NEON.
Пожалуйста, проверьте:

1. запуск после установки и после reboot;
2. TUN-режим для устройств в LAN;
3. локальный SOCKS5-режим;
4. восстановление после краткого обрыва WAN;
5. остановку сервиса и возврат обычного маршрута.

Создайте отчёт в [GitHub Issues](https://github.com/danusha2345/csqtt-android/issues)
и приложите модель роутера, версию OpenWrt, `uname -m`, способ установки,
выбранный режим и очищенный вывод `logread -e csqtt`. Пароль подключения и
VK-хеши публиковать нельзя.

## Остановка и возврат обычного маршрута

```sh
/etc/init.d/csqtt stop
/etc/init.d/csqtt disable
```

Сервис удалит созданные им policy rule, default route таблицы CSQTT и firewall
rules. Конфигурация и бинарник останутся на месте для последующего запуска.
