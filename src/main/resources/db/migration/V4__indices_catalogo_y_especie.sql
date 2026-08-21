-- ===========================================================================
-- V4 - Indices que faltaban, e indice de especie que existia sin servir.
--
-- (1) ANIMAL_ADOPCION no tenia NI UN indice fuera de su clave primaria, cuando
--     ALERTA tiene cuatro, AVISTAMIENTO cuatro y MENSAJE tres. No es que
--     estuviera poco cubierta: es que se quedo sin cubrir cuando se anadio el
--     catalogo. Dos consultas la recorren entera hoy:
--
--     - el catalogo y el feed (RF-09, RF-22), que filtran por
--       estado = 'DISPONIBLE' y ordenan por created_at DESC;
--     - cualquier lectura por protectora_id, porque PostgreSQL no indexa las
--       claves ajenas por su cuenta —solo las primarias y las unicas—, asi que
--       la clave ajena de una tabla se recorre en secuencial mientras nadie le
--       ponga su indice.
--
-- (2) IDX_ALERTA_ESPECIE ERA UN INDICE MUERTO. La consulta de duplicados
--     (RF-25) compara "lower(a.especie) = lower(:especie)", y un B-tree sobre
--     la columna en crudo no puede responder a una comparacion sobre
--     lower(columna): para el planificador son expresiones distintas. Se
--     comprobo con EXPLAIN forzando enable_seqscan=off, y el plan se iba al
--     indice de estado con la especie como filtro, ignorando el suyo.
--
--     Se sustituye en vez de anadirse al lado porque ninguna consulta del
--     proyecto compara especie distinguiendo mayusculas: mantener los dos
--     dejaria uno inutil cobrandose su parte en cada INSERT y cada UPDATE.
--
-- NO se anaden aqui los indices funcionales de ANIMAL_ADOPCION sobre
-- lower(especie) y lower(zona_geografica), que CatalogoAdopcionSpec tambien
-- usa. El de zona no serviria —es un LIKE '%...%', y un comodin por delante no
-- lo resuelve ningun B-tree, haria falta trigramas (pg_trgm)— y el de especie
-- solo tiene sentido medido sobre un catalogo con volumen real, que hoy no
-- existe. Queda anotado para cuando lo haya.
-- ===========================================================================

-- (1)
CREATE INDEX idx_animal_estado_created
    ON animal_adopcion USING btree (estado, created_at);

CREATE INDEX idx_animal_protectora
    ON animal_adopcion USING btree (protectora_id);

-- (2)
DROP INDEX idx_alerta_especie;

CREATE INDEX idx_alerta_especie_lower
    ON alerta USING btree (lower(especie));
