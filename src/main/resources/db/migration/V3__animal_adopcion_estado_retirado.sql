-- ===========================================================================
-- V3 - ANIMAL_ADOPCION admite el estado RETIRADO.
--
-- Retirar un animal del catalogo (RF-07) era un DELETE de la fila. Como
-- MATCH_REGISTRO.publicacion_id es una referencia polimorfica y no lleva clave
-- ajena, la base de datos no lo impedia: el borrado se llevaba por delante la
-- publicacion de la que colgaban matches, chats y mensajes, dejandolos
-- apuntando a un id inexistente. ChatService ya sabia esquivarlos —los omitia
-- del listado y devolvia 404 en el detalle—, pero eso es tapar el sintoma: la
-- consecuencia visible era que a un ciudadano le desaparecia de golpe una
-- conversacion en curso, sin poder leer siquiera lo que ya se habia dicho.
--
-- Con RETIRADO la fila se queda, el chat sigue en pie y el catalogo deja de
-- mostrarlo sin necesidad de un filtro nuevo: catalogo y feed ya exigen
-- estado = DISPONIBLE, asi que ningun estado distinto aparece en ellos.
--
-- Es el mismo patron que ALERTA, que pasa a ELIMINADA en vez de borrarse.
--
-- Se recrea el CHECK porque PostgreSQL no permite ampliarlo en el sitio. El
-- nombre se conserva para que siga coincidiendo con el que genera Hibernate a
-- partir de @Enumerated en la entidad.
-- ===========================================================================

ALTER TABLE animal_adopcion
    DROP CONSTRAINT animal_adopcion_estado_check;

ALTER TABLE animal_adopcion
    ADD CONSTRAINT animal_adopcion_estado_check CHECK (
        (estado)::text = ANY ((ARRAY[
            'DISPONIBLE'::character varying,
            'ADOPTADO'::character varying,
            'RETIRADO'::character varying])::text[]));
