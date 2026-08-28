-- ---------------------------------------------------------------------------
-- V12 - PROTECTORA.es_persona_fisica: quien hay detras de la protectora (RF-19)
--
-- POR QUE HACE FALTA PREGUNTARLO. La baja de cuenta anonimiza al usuario pero
-- conservaba intactos el nombre de la organizacion y su direccion, con el
-- argumento de que son datos de una entidad y no de una persona, y de que son
-- los que explican de donde salio cada animal publicado.
--
-- El argumento vale para una asociacion registrada. No vale para el caso, muy
-- comun en Espana, de una protectora que es UNA PERSONA PARTICULAR rescatando
-- animales por su cuenta, sin estructura legal: esa persona pone su propio
-- nombre como "organizacion" y su domicilio particular como "direccion". Para
-- ella, esas dos columnas son datos personales tan identificables como su
-- telefono, y una baja que no los tocara no borraria nada de lo que la
-- identifica. Seguirian apareciendo en cada ficha de animal que publico,
-- indefinidamente.
--
-- SE DECLARA, NO SE DEDUCE. Nada en una cadena de texto distingue con fiabilidad
-- "Huellas de Getafe" de "Maria Gomez Ruiz", y equivocarse tiene coste en las dos
-- direcciones: borrar de mas deja el historico sin procedencia, borrar de menos
-- deja expuesta a una persona. Lo pregunta el registro y lo responde quien se da
-- de alta, que es la unica que lo sabe.
--
-- LAS FILAS EXISTENTES QUEDAN EN false, y esa es una limitacion conocida que
-- conviene decir en voz alta: no hay forma retroactiva de saber cuales eran
-- personas fisicas. Una protectora registrada antes de esta migracion que lo sea
-- seguira sin proteccion hasta que alguien lo declare a mano. No se elige true
-- como defecto porque seria peor: anonimizaria el nombre de asociaciones reales
-- al darse de baja, dejando sin procedencia el historico de adopciones que este
-- diseno se esfuerza en conservar. Entre dejar un dato de mas y borrar uno que
-- hacia falta, se deja el de mas y se documenta.
--
-- EN TRES PASOS, como V7, porque la tabla tiene filas y un ADD COLUMN NOT NULL
-- sin defecto las rechazaria. El DEFAULT se deja puesto ademas de rellenar: aqui
-- SI tiene sentido, a diferencia de V7, porque el valor por defecto es una
-- respuesta valida —"no, es una entidad"— y no el disimulo de un campo que
-- alguien se ha olvidado de poner.
-- ---------------------------------------------------------------------------

ALTER TABLE protectora
    ADD COLUMN es_persona_fisica boolean;

UPDATE protectora
SET es_persona_fisica = false
WHERE es_persona_fisica IS NULL;

ALTER TABLE protectora
    ALTER COLUMN es_persona_fisica SET NOT NULL,
    ALTER COLUMN es_persona_fisica SET DEFAULT false;
