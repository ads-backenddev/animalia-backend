#!/bin/sh
# =============================================================================
# Entrada del contenedor: prepara la carpeta de fotos y arranca la aplicacion
# como el usuario sin privilegios "spring".
#
# POR QUE EMPIEZA COMO ROOT. Railway monta los volumenes como root, y la
# aplicacion corre como "spring": sin esto no puede crear su carpeta de fotos
# y no arranca (AlmacenFotosService lo comprueba al iniciar). Lo unico que se
# hace como root es crear esa carpeta y darsela a "spring"; la aplicacion no
# llega a ejecutarse nunca como root.
#
# POR QUE su-exec Y NO su. su-exec hace exec: java pasa a ser el proceso 1 y
# recibe directamente el SIGTERM con el que Railway para el contenedor, asi que
# el apagado es ordenado. Con su, java seria un hijo que no recibe la senal.
#
# Solo la carpeta, sin -R: los archivos de dentro los crea ya "spring", y
# recorrer todas las fotos en cada arranque no aportaria nada.
# =============================================================================
set -eu

if [ "$(id -u)" = "0" ]; then
    if [ -n "${FOTOS_DIR:-}" ]; then
        mkdir -p "$FOTOS_DIR"
        chown spring:spring "$FOTOS_DIR"
    fi
    exec su-exec spring java -jar /app/app.jar "$@"
fi

exec java -jar /app/app.jar "$@"
