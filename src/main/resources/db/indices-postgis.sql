-- ---------------------------------------------------------------------------
-- Indices espaciales que Hibernate no sabe generar.
--
-- @Index de JPA solo produce indices B-tree, y un B-tree sobre una columna
-- geometry no sirve para nada: ST_DWithin y ST_Distance necesitan GiST. Sin
-- ellos, cada POST /api/alertas (comprobacion de duplicados, RF-25) y cada
-- GET /api/alertas/mapa (RF-04) hacen un recorrido secuencial de la tabla, que
-- es justo lo que RNF-02 no puede permitirse con 100 usuarios simultaneos.
--
-- Se ejecuta en cada arranque, despues de que Hibernate haya creado o
-- actualizado el esquema (spring.jpa.defer-datasource-initialization=true).
-- IF NOT EXISTS lo hace idempotente.
--
-- El proyecto no usa migraciones (ni Flyway ni Liquibase); cuando se anadan,
-- este fichero es lo primero que debe convertirse en una migracion.
-- ---------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_alerta_ubicacion_gist
    ON alerta USING GIST (ultima_ubicacion);

CREATE INDEX IF NOT EXISTS idx_avistamiento_ubicacion_gist
    ON avistamiento USING GIST (ubicacion);
