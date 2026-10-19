-- ---------------------------------------------------------------------------
-- V7 - AVISTAMIENTO.estado: retirar en vez de borrar (RF-11)
--
-- El administrador puede eliminar una publicacion que incumpla las normas. Para
-- las alertas ya existia ELIMINADA y para los animales RETIRADO; el
-- avistamiento era el unico de los tres sin estado, asi que la unica forma de
-- retirarlo habria sido un DELETE fisico.
--
-- POR QUE NO UN DELETE. MATCH_REGISTRO.publicacion_id apunta al avistamiento sin
-- clave ajena, de modo que borrar la fila dejaria huerfanos sus matches y, con
-- ellos, el chat y los mensajes que colgaran de cada uno. Y no daria error:
-- ChatRepository.buscarPorParticipante resuelve al autor del avistamiento con un
-- EXISTS contra esta tabla, asi que al desaparecer la fila la conversacion
-- dejaria de aparecer en su lista de chats sin un solo aviso. Alguien perderia
-- una conversacion en curso y ni siquiera podria releer lo ya hablado.
--
-- Moderar no exige borrar: exige que el contenido deje de verse. El estado
-- consigue eso y ademas conserva constancia de que se retiro, que es lo que un
-- administrador necesita si la decision se reclama (RF-26).
--
-- EN TRES PASOS, y no en un ALTER con DEFAULT, porque la columna nace NOT NULL
-- sobre una tabla que ya tiene filas: primero se añade admitiendo nulos, luego
-- se rellenan las existentes como VISIBLE —que es lo que eran— y solo entonces
-- se exige el NOT NULL. La columna se queda sin DEFAULT a proposito: el estado
-- inicial lo fija AvistamientoService al registrar, y un DEFAULT en la base de
-- datos seria una segunda fuente para la misma decision.
-- ---------------------------------------------------------------------------

ALTER TABLE avistamiento ADD COLUMN estado character varying(20);

UPDATE avistamiento SET estado = 'VISIBLE' WHERE estado IS NULL;

ALTER TABLE avistamiento ALTER COLUMN estado SET NOT NULL;

ALTER TABLE avistamiento ADD CONSTRAINT avistamiento_estado_check
    CHECK (estado IN ('VISIBLE', 'ELIMINADO'));
