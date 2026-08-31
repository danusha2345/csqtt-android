<div align="center">

# CSQTT Android + Server

[![GitHub](https://img.shields.io/badge/GitHub-danusha2345%2Fcsqtt--android-181717?style=for-the-badge&logo=github)](https://github.com/danusha2345/csqtt-android)
[![Boosty](https://img.shields.io/badge/Boosty-Support%20development-FF7143?style=for-the-badge&logo=boosty&logoColor=white)](https://boosty.to/danusha/donate)

![Android](https://img.shields.io/badge/Android-SDK_26--37-3DDC84?style=flat-square&logo=android&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-1.97.1-000000?style=flat-square&logo=rust&logoColor=white)
![License](https://img.shields.io/badge/license-PolyForm_Noncommercial-blue?style=flat-square)

</div>

Поддерживаемая сборка CSQTT для Android и Linux-сервера. Клиент создаёт
системный VPN-интерфейс и передаёт трафик через TURN/RTP, маскируя транспорт под
зашифрованный медиатрафик звонка.

- Репозиторий: [`danusha2345/csqtt-android`](https://github.com/danusha2345/csqtt-android)
- Релизы Android/server: [GitHub Releases](https://github.com/danusha2345/csqtt-android/releases)
- Windows/Linux desktop-клиенты: [`danusha2345/csqtt-vpn`](https://github.com/danusha2345/csqtt-vpn)
- Поддержать разработку: [Boosty](https://boosty.to/danusha/donate)
- Исходный проект: [`amurcanov/csqtt`](https://github.com/amurcanov/csqtt)

## Актуальный релиз

Версия: **2.1.10**. Android package: `csqtt.quic.amurcanov`.

Каждый пользователь разворачивает собственный сервер и вводит его адрес в формате
`host:46010`. Адрес и пароль нашей инфраструктуры в Git и Releases не публикуются.
Для установки на большинство устройств используйте `CSQTT-2.1.10-universal.apk`
из раздела Releases.

## Возможности

- Android VPN на `arm64-v8a` и `armeabi-v7a`;
- OpenWrt-клиент с нативным TUN и `procd` для `x86_64`, `aarch64` и `armv7`;
- TURN через UDP или TCP/TLS;
- режим локального SOCKS5 `CONNECT` на `127.0.0.1` без Android VPN;
- маскировка `audio` и `video`;
- ручные и автоматические режимы VK call links/hashes;
- от 9 до 126 workers;
- автопауза VPN при подключении к Wi-Fi;
- профили, исключения приложений и системный quick settings tile;
- Rust server со встроенной web-панелью, SQLite/WAL и управлением клиентами;
- deploy на VPS через SSH из Android-приложения.
- защищённое обновление клиентского порта и VK-хешей при подключении и каждые 6 часов.

Подробный список изменений находится в [CHANCHELOG.md](CHANCHELOG.md).

## Сборка Android

Требуются JDK 17, Android SDK 37, Android NDK r28c и Rust 1.97.1.

Создайте неотслеживаемый `local.properties` по шаблону
[`local.properties.example`](local.properties.example), затем выполните:

```bash
bash gradlew testDebugUnitTest lintDebug lintRelease assembleRelease --no-daemon
```

Release-сборка требует явно настроенный keystore и не переходит автоматически на
debug-подпись. Перед ней также обязательно должны пройти native provenance gate и
проверка встроенного server binary. На Linux полный native workflow запускается так:

```bash
scripts/build_android_native.sh --tests
cp rust-server/dist/csqtt app/src/main/assets/csqtt
scripts/build_apk.sh
```

APK создаются в `app/build/outputs/apk/release/`.

## Сборка Rust-компонентов

Клиент:

```bash
cd rust-client
cargo +1.97.1 test --locked
cargo +1.97.1 ndk -t arm64-v8a -t armeabi-v7a -P 26 build --release --locked
```

Linux server x86_64/musl:

```bash
cd rust-server
cargo +1.97.1 test --locked
cargo +1.97.1 zigbuild --release --locked --target x86_64-unknown-linux-musl
```

## OpenWrt

Для роутеров опубликован первый beta-порт с нативным Linux TUN, UCI-конфигом,
`procd`-сервисом, policy routing для LAN и дополнительным режимом локального
SOCKS5. Поддерживаются:

- `x86_64`;
- `aarch64` (`aarch64_generic`);
- `armv7` hard-float с NEON (`arm_cortex-a7_neon-vfpv4`).

Скачать готовые файлы: [CSQTT OpenWrt 0.1.0 Beta](https://github.com/danusha2345/csqtt-android/releases/tag/openwrt-v0.1.0-beta.1).

Полная русская инструкция по выбору архитектуры, установке, UCI-настройке,
проверке и остановке: [openwrt/README.md](openwrt/README.md).

- Для OpenWrt 25.12+ используйте универсальный `.tar.gz`.
- Для OpenWrt 24.10 можно использовать `.tar.gz` или готовый `.ipk`.
- MIPS/MIPSel пока не поддерживаются.

### Нужны тестировщики

Первый OpenWrt-порт опубликован как beta. Если у вас есть роутер `x86_64`,
`aarch64` или `armv7` с NEON, пожалуйста, установите подходящую сборку по
инструкции, проверьте TUN/SOCKS5, перезапуск сервиса и работу устройств в LAN,
а результат сообщите в [GitHub Issues](https://github.com/danusha2345/csqtt-android/issues).

В отчёте укажите модель роутера, версию OpenWrt, вывод `uname -m`, выбранный
режим и очищенный `logread -e csqtt`. Не публикуйте пароль подключения и
VK-хеши.

## Безопасность и ограничения

- `local.properties`, keystore, адреса/пароли частных серверов и VK call links не должны попадать
  в Git или публичные отчёты;
- SSH host key при первом deploy сохраняется в приватном хранилище приложения; изменение ключа
  того же `host:port` блокируется как возможная подмена сервера;
- config sync не передаёт пароль в URL: запрос подписан HMAC, ответ зашифрован AES-256-CTR и
  защищён отдельным HMAC; ключи выводятся через HKDF из connection password;
- SOCKS5 слушает только loopback, поддерживает TCP `CONNECT` и удалённый DNS; UDP Associate и
  доступ к private/link-local адресам VPS намеренно запрещены;
- сервер создаёт конфигурацию с режимом `0700`, SQLite/WAL/SHM — `0600`;
- полный TURN e2e требует действующей VK call link/hash;
- проект предназначен только для некоммерческого использования.

## Происхождение и лицензия

Это поддерживаемый fork проекта
[`amurcanov/csqtt`](https://github.com/amurcanov/csqtt). Авторство исходного
проекта сохраняется. Код распространяется на условиях
[PolyForm Noncommercial License 1.0.0](LICENSE); коммерческое использование,
перепродажа и интеграция в платные сервисы запрещены.
