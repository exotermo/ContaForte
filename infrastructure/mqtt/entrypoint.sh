#!/bin/sh
# Gera o arquivo de senhas a partir do ambiente (nada de segredo versionado) e inicia o broker.
set -eu
: "${MQTT_USER:?}" "${MQTT_PASSWORD:?}"
# /mosquitto/config é montado só com o mosquitto.conf (somente leitura); o passwd vai em /tmp e o conf aponta para ele.
PASSWD_FILE=/tmp/passwd
# Num reinício o arquivo da subida anterior continua ali, já pertencendo ao usuário mosquitto, e o kernel
# (fs.protected_regular) impede até o root de reescrevê-lo em /tmp. Apagar e recriar evita o loop de reinício.
rm -f "$PASSWD_FILE"
: > "$PASSWD_FILE"
mosquitto_passwd -b "$PASSWD_FILE" "$MQTT_USER" "$MQTT_PASSWORD"
# O broker descarta privilégios para o usuário mosquitto; ele precisa ser dono do arquivo.
if [ "$(id -u)" = "0" ]; then chown mosquitto:mosquitto "$PASSWD_FILE"; fi
chmod 0600 "$PASSWD_FILE"
sed "s#/mosquitto/config/passwd#$PASSWD_FILE#" /mosquitto/config/mosquitto.conf > /tmp/mosquitto.conf
exec mosquitto -c /tmp/mosquitto.conf
