# Исправления ревью 2.5 от 24 октября 2026

Основа: `feature/upstream-2.5-all`, Android/server `353b72ad`, desktop `05aff232`.
Контракт остаётся CSQTT-WIRE-4 с парольной привязкой DTLS. Это исправления рабочей
ветки, не публикация релиза и не деплой. Старые клиенты 2.1 не являются целевой
совместимостью этой ветки.

| Пункт ревью | Исправление | Проверка |
|---|---|---|
| R1 | Импорт сохраняет только свободные клиентские адреса выбранной подсети. Конфликты, адрес владельца и чужая подсеть получают новые IP. При исчерпании пула импорт полностью отклоняется. Загрузка старой БД исправляет дубликаты. | Unit и настоящий API: дубликат, reserved/foreign IP, полный пул, откат |
| R2 | `isWhite` относится к приложениям, `domainsIsWhite` — к доменам. В native передаётся именно доменный режим. | JVM: обе противоположные комбинации режимов |
| R3 | Кеш IP больше не даёт самостоятельного разрешения обходить VPN: новые соединения проверяются по текущим правилам. | Новый TCP SYN после удаления домена, native regression |
| R4 | Android `excludeRoute` использует только включённый IP blacklist. Изменение IP-правил пересоздаёт VPN-маршруты, включая обновления с сервера. | JVM snapshot disabled/enabled; физический packet capture не выполнялся |
| R5 | API больше не принимает сохранённый на старте master password. | После ротации старые X-API-Key и Bearer получают 401, новый ключ — 200 |
| R6 | START, восстановление и observer читают единый снимок маршрутизации. Старый observer четырёх полей удалён. Отключённые списки остаются отключёнными. | JVM snapshot и штатный Android suite |
| R7 | SOCKS-форма держит локальное состояние редактирования, сохраняет согласованные значения и привязывает запись к редактируемому профилю. | JVM проверки профиля; signed APK: алфавит и пароль точно совпали в GUI export |
| R8 | Export использует эффективные defaults SSH port=22 и Docker=false; импорт принимает старый пустой SSH port как 22. | Roundtrip нового профиля со всеми секциями |
| R9 | Backup включает SOCKS mode/port/LAN/username, пароль — только при includeSecrets. Импорт валидирует значения до изменения настроек. | Восстановление в другом профиле, отсутствие пароля в публичном экспорте, отказ некорректному порту |
| R10 | При secret path убраны публичные root aliases manifest и service worker; ресурсы доступны внутри secret prefix. | Неавторизованный HTTP probe и scoped manifest |
| R11 | Group-admin меняет подготовленную копию клиента только после валидации всех полей. | Отклонённые workers/hash не меняют ни имя, ни срок |
| R12 | Desktop и OpenWrt передают `--exact-workers`. | Native startup: 3→3, 18→18, 99→99; сборка desktop и сверка OpenWrt init script в пакетах |
| R13 | `CSQTT_TUN_SUBNET` задаёт адрес интерфейса, владельца, пул клиентов, route table, firewall и отображаемые адреса. Недопустимое значение останавливает startup до изменения сети. | Реальный интерфейс 10.77.88.1; импорт в этот пул; WRAP/DTLS и ICMP fragmentation в нестандартной подсети |
| R14 | SOCKS учитывает пользовательские байты после успешной отправки и проверки входящей последовательности, без control frames и offset overhead. | 4096-byte echo и рост обоих счётчиков в WRAP/DTLS E2E |
| R15 | Desktop VersionInfo описывает фактический CSQTT-WIRE-4 и парольную привязку DTLS. | Нативное окно настроек Linux |
| R16 | Select задаёт собственное appearance и контрастные цвета. | Настоящий WebKitGTK: закрытый select и раскрытое меню читаемы |
| R17 | Quick Settings и widget используют общую `requiresVpnPermission`: SOCKS не требует VPN consent. | Policy test; QS запустила SOCKS service при ACTIVATE_VPN=ignore |
| R18 | Создание, импорт и смена master password отклоняют `|` и управляющие символы. Startup не принимает БД с недопустимыми tunnel passwords. | Unit и настоящий API, включая атомарный отказ смешанному импорту |

## Повторение проверок

- `cargo +1.97.1 test --manifest-path rust-client/Cargo.toml --locked`
- `cargo +1.97.1 test --manifest-path rust-server/Cargo.toml --locked`
- Для обоих crates: `cargo +1.97.1 clippy --manifest-path … --locked --all-targets -- -D warnings`.
- `bash gradlew --no-configuration-cache :app:testDebugUnitTest :app:lintDebug`
- `CSQTT_LOCAL_E2E_SERVER=/absolute/path/to/csqtt bash scripts/test_panel_regressions.sh`
- `CSQTT_LOCAL_E2E_SERVER=/absolute/path/to/csqtt bash scripts/test_local_transport.sh`
- Для нестандартной подсети к предыдущей команде добавить `CSQTT_TUN_SUBNET=10.77.88.0/24`.
- `python3 scripts/test_openwrt_firewall.py --require-netns`, `python3 scripts/test_release_safety.py`, `bash scripts/test_tunnel_subnet.sh`.
- В desktop: `go test -race ./...`, `go vet ./...`, `npm --prefix frontend test`, `npm --prefix frontend run build`.

Оба серверных shell harness запускают сервер в отдельном user/network namespace
с временной БД, синтетическими паролями и завершают его при выходе. Не направлять
Python API-пробы на production. QEMU проверяет ARM64/ARMv7 серверы; это не проверка
физического роутера. Сборка Windows не заменяет GUI/UAC/Wintun-прогон на Windows.

Доказательства локального прогона: `/home/danik/storage/csqtt-fixes-20261024`.

## Итог локальной проверки

372 теста Rust-клиента, 158 сервера, 233 Android прошли. Clippy обоих crates,
Go race/vet, 5 frontend tests, 23 OpenWrt и 4 release-safety tests — без ошибок.
По два транспортных E2E прошли с x86_64, ARM64/QEMU и ARMv7/QEMU сервером;
ещё два — с нестандартной подсетью. API regression harness прошёл полностью.

На API36-эмулятор установлен новый подписанный APK. Набранные username и пароль
сверены с реальным файлом экспорта; этот файл успешно импортирован через системный
picker. Плитка QS дошла до запуска SOCKS service при невыданном VPN-разрешении;
это проверка запуска, не успешной сессии VK TURN. В Linux проверены настоящие
Wails/WebKitGTK controls. Windows EXE и core пересобраны, но GUI/UAC/Wintun на
настоящей Windows в этой среде недоступны. Физический Android/OpenWrt и live VPS
не использовались. Тестовые процессы остановлены.

Все четыре APK прошли проверку подписи, package/version, содержимого native и
server assets, 16K ELF alignment. Три OpenWrt IPK и tar проверены чтением обратно:
бинарники и init/firewall scripts совпадают с исходными сборками. Native provenance
обновлён; контрольные суммы находятся рядом со сборками. Номера версий не повышались:
это локальные проверочные сборки, не опубликованные релизы.
