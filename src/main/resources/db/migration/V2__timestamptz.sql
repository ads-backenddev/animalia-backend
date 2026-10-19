-- ===========================================================================
-- V2 - Los instantes reales pasan a TIMESTAMPTZ.
--
-- EL PROBLEMA. Todas las marcas de tiempo del modelo eran "timestamp without
-- time zone" y en Java LocalDateTime, es decir, hora de pared sin ninguna
-- referencia: "las 10:00" sin decir de donde. Mientras la aplicacion corre en
-- un portatil en Europe/Madrid y nadie compara nada, se sostiene. Deja de
-- sostenerse en cuanto la API se despliega en Railway, cuyos contenedores van
-- en UTC: la misma fila escrita en dos maquinas significa dos instantes
-- distintos, y no hay dato en la columna que permita saber cual.
--
-- DONDE MORDIA DE VERDAD. En la deteccion de alertas duplicadas (RF-25), que
-- compara fecha_avistamiento con "ahora menos 24 horas". Ese calculo se hacia
-- con LocalDateTime.now(), asi que:
--   - en Madrid restaba 24 h sobre las 12:00 locales y en Railway sobre las
--     10:00 UTC, dando ventanas desplazadas dos horas entre entornos;
--   - la noche del cambio de hora restaba 24 horas de reloj, que esa noche son
--     23 o 25 horas reales.
-- Con timestamptz e Instant, la columna guarda un punto en la linea del tiempo
-- y la resta son 24 horas de verdad en cualquier maquina y cualquier fecha.
--
-- QUE SE MIGRA Y QUE NO. Se migra todo lo que es un instante: los created_at de
-- las once tablas que lo tienen, usuario.password_changed_at —que se compara
-- con el claim "iat" de un JWT, que ya es un instante— y
-- alerta.fecha_avistamiento, que es el otro extremo de la comparacion de
-- RF-25: dejarla en timestamp habria dejado el fallo intacto.
--
-- NO se migra limite_match_diario.fecha, que es DATE y debe seguir siendolo:
-- la cuota de RF-24 se cuenta por dia natural, que es una fecha de calendario y
-- no un instante.
--
-- SOBRE EL "AT TIME ZONE 'Europe/Madrid'". Los valores ya escritos no dicen de
-- que zona son; hay que decidirlo. Se interpretan como hora peninsular porque
-- es la de la maquina de desarrollo donde se escribieron —el unico entorno con
-- datos hasta hoy—. En una base vacia (CI, Railway) el USING no convierte nada
-- porque no hay filas.
-- ===========================================================================

ALTER TABLE usuario
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid',
    ALTER COLUMN password_changed_at TYPE timestamptz
        USING password_changed_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE protectora
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE alerta
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid',
    ALTER COLUMN fecha_avistamiento TYPE timestamptz
        USING fecha_avistamiento AT TIME ZONE 'Europe/Madrid';

ALTER TABLE avistamiento
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE animal_adopcion
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE match_registro
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE chat
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE mensaje
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE valoracion
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE reporte
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

ALTER TABLE notificacion
    ALTER COLUMN created_at TYPE timestamptz
        USING created_at AT TIME ZONE 'Europe/Madrid';

-- El indice de avistamiento.created_at se reconstruye solo al cambiar el tipo
-- de la columna; los de mensaje (chat_id, created_at) tambien. No hay que
-- volver a crearlos a mano.
