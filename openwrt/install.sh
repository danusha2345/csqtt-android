#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
	echo "Запустите установку от root." >&2
	exit 1
fi

bundle_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
binary=$bundle_dir/usr/bin/csqtt-client
[ -x "$binary" ] || { echo "В архиве нет usr/bin/csqtt-client" >&2; exit 1; }

if [ -x /etc/init.d/csqtt ]; then
	/etc/init.d/csqtt stop 2>/dev/null || true
fi

mkdir -p /usr/bin /usr/libexec /etc/init.d /etc/config
cp "$binary" /usr/bin/csqtt-client
cp "$bundle_dir/usr/libexec/csqtt-tun" /usr/libexec/csqtt-tun
cp "$bundle_dir/etc/init.d/csqtt" /etc/init.d/csqtt
if [ ! -f /etc/config/csqtt ]; then
	cp "$bundle_dir/etc/config/csqtt" /etc/config/csqtt
	chmod 0600 /etc/config/csqtt
else
	echo "Существующий /etc/config/csqtt сохранён."
fi
chmod 0755 /usr/bin/csqtt-client /usr/libexec/csqtt-tun /etc/init.d/csqtt

echo "CSQTT установлен. Теперь заполните /etc/config/csqtt и выполните:"
echo "  /etc/init.d/csqtt enable"
echo "  /etc/init.d/csqtt restart"
echo "  logread -e csqtt"
