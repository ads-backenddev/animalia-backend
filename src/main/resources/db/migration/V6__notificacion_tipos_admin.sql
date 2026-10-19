-- ---------------------------------------------------------------------------
-- V6 - Tipos de notificacion de las decisiones del administrador (RF-11)
--
-- El administrador que bloquea una cuenta, la desbloquea o rechaza una
-- protectora tiene que poder avisar al afectado. PROTECTORA_VERIFICADA ya
-- cubria la aprobacion; los otros tres casos no tenian tipo.
--
-- POR QUE HACE FALTA UNA MIGRACION PARA AÑADIR VALORES A UN ENUM DE JAVA.
-- notificacion.tipo no es un enum de PostgreSQL sino un varchar con un CHECK
-- que Hibernate genero listando los valores uno a uno. Añadir constantes al
-- enum de Java no toca esa restriccion, asi que la primera notificacion de un
-- tipo nuevo fallaria al insertarse. Con ddl-auto=validate tampoco hay quien lo
-- arregle solo: validate compara columnas, tipos y nulabilidad, no CHECKs.
--
-- Se reescribe la restriccion entera en vez de intentar ampliarla porque
-- PostgreSQL no sabe añadir valores a un CHECK ya creado: hay que sustituirlo.
-- Los seis tipos anteriores se repiten tal cual, para que la lista siga siendo
-- la definicion completa y no haya que leer dos migraciones para saber cuales
-- se admiten.
--
-- El ancho de la columna no cambia: el valor mas largo que se añade,
-- CUENTA_DESBLOQUEADA, son 19 caracteres y la columna admite 30.
-- ---------------------------------------------------------------------------

ALTER TABLE notificacion DROP CONSTRAINT notificacion_tipo_check;

ALTER TABLE notificacion ADD CONSTRAINT notificacion_tipo_check CHECK (tipo IN (
    'ALERTA_CERCANA', 'NUEVO_MATCH', 'NUEVO_MENSAJE',
    'PROTECTORA_VERIFICADA', 'ADOPCION_CONFIRMADA', 'REPORTE_RESUELTO',
    'PROTECTORA_RECHAZADA', 'CUENTA_BLOQUEADA', 'CUENTA_DESBLOQUEADA'));
