-- ---------------------------------------------------------------------------
-- V9 - VALORACION unica por usuario y protectora, y adios a la media guardada
--      (RF-23)
--
-- DOS CAMBIOS QUE VAN JUNTOS PORQUE SON LA MISMA DECISION: la valoracion de un
-- usuario sobre una protectora es un dato unico, y la media es una consulta, no
-- un dato.
--
-- 1. UNIQUE (usuario_id, protectora_id)
--
-- RF-23 habla de "la valoracion" de un usuario sobre una protectora, en
-- singular: valorar dos veces es cambiar de opinion, no emitir dos votos. Sin
-- esta restriccion, ValoracionService puede hacer todo el trabajo de buscar la
-- valoracion previa y actualizarla, y aun asi dos peticiones simultaneas del
-- mismo usuario crearian dos filas: las dos leerian "no existe" antes de que
-- ninguna insertara. La media quedaria inflada por un usuario que ha votado
-- dos veces, y ninguna prueba de un solo hilo lo detectaria.
--
-- Con la restriccion en la base de datos, la carrera acaba en un error de
-- integridad en vez de en un dato corrupto. Es el mismo criterio que el resto
-- del esquema: lo que no puede pasar, que no lo permita la tabla.
--
-- LA LIMPIEZA PREVIA ES UNA RED, NO UNA NECESIDAD. Hoy la tabla esta
-- necesariamente vacia, porque hasta este bloque no existia forma de escribir
-- en ella. Pero una migracion que falla al aplicarse es una forma pesima de
-- descubrir lo contrario, asi que se dejan las filas mas recientes de cada par
-- y se borran las anteriores. Si la tabla esta vacia, no hace nada.
--
-- 2. DROP COLUMN protectora.valoracion_media
--
-- Era una columna derivada que NADIE mantenia: ningun codigo la escribia, asi
-- que era nula para todas las protectoras y lo habria seguido siendo mientras
-- se acumulaban valoraciones reales debajo. Una media guardada solo es util si
-- alguien se encarga de recalcularla en cada alta, baja y modificacion de una
-- valoracion, y ese "alguien" es exactamente el codigo que se olvida.
--
-- La media pasa a calcularse con AVG(puntuacion) y COUNT(*) donde se lee: en el
-- panel de la protectora (RF-10) y en el detalle de un animal en adopcion, que
-- es donde un ciudadano ve hoy la reputacion de quien lo publica. Asi es
-- correcta por construccion y no puede desincronizarse, que es justo lo que le
-- pasaba a la columna. El coste es una agregacion sobre una tabla pequena e
-- indexada por protectora_id a traves de su clave ajena.
--
-- Si algun dia el volumen lo pidiera, la solucion no es resucitar la columna
-- sino una vista materializada o una cache con invalidacion explicita, que al
-- menos declara que es una copia.
-- ---------------------------------------------------------------------------

-- 1. Una sola valoracion por usuario y protectora ---------------------------

DELETE FROM valoracion v
WHERE v.id NOT IN (
    SELECT MAX(id) FROM valoracion GROUP BY usuario_id, protectora_id
);

ALTER TABLE valoracion
    ADD CONSTRAINT uk_valoracion_usuario_protectora UNIQUE (usuario_id, protectora_id);

-- 2. La media deja de guardarse ---------------------------------------------

ALTER TABLE protectora
    DROP COLUMN valoracion_media;
