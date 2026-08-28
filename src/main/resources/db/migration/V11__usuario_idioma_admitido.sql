-- ---------------------------------------------------------------------------
-- V11 - USUARIO.idioma solo admite los idiomas que la aplicacion soporta
--       (RF-18)
--
-- HASTA AHORA LA COLUMNA ACEPTABA CUALQUIER COSA de hasta diez caracteres.
-- "klingon" era un idioma perfectamente valido para la base de datos, y nadie
-- lo habria notado: el idioma no se usa todavia en ninguna consulta, asi que un
-- valor absurdo no rompe nada hoy y se descubre el dia que alguien intente
-- traducir la interfaz y se encuentre con codigos que no existen.
--
-- El endpoint de RF-18 valida contra el enum Idioma antes de escribir, pero esa
-- comprobacion vive en la aplicacion: se la salta cualquiera que escriba por SQL,
-- y tambien se la saltaron todas las filas creadas antes de este bloque. El CHECK
-- es lo que queda en pie en los dos casos.
--
-- POR QUE UN CHECK Y NO UN ENUM DE VERDAD. El valor que viaja es un codigo
-- ISO 639-1, que se escribe en minuscula —"es", "ca"— y ya esta en el contrato de
-- la API y en las filas existentes. Mapearlo con @Enumerated(STRING) escribiria
-- "ES" y "CA", asi que habria que elegir entre romper el contrato o anadir un
-- convertidor para deshacer lo que el enum acaba de hacer. La columna guarda el
-- codigo y el enum Idioma es la lista de lo admitido; este CHECK es la misma
-- lista del lado de la base de datos.
--
-- LA NORMALIZACION PREVIA NO ES OPCIONAL. A diferencia de otras migraciones de
-- este proyecto donde la limpieza era una red por si acaso, aqui hace falta de
-- verdad: la tabla tiene filas y sus idiomas nunca se validaron, asi que el
-- ALTER fallaria con cualquier valor fuera de la lista y dejaria el arranque
-- roto. Se hace en dos pasos, primero bajar a minusculas lo que ya es valido
-- —"ES" es espanol, no un error— y despues llevar al idioma por defecto lo que
-- siga sin reconocerse.
--
-- LA COLUMNA SIGUE ADMITIENDO NULL, y es deliberado: el CHECK de PostgreSQL da
-- por buena una fila con NULL, asi que no hace falta excluirlo. En la practica
-- nunca llega a ser nulo porque el registro siempre pone uno, pero convertirlo en
-- NOT NULL seria un cambio de la entidad que RF-18 no pide.
-- ---------------------------------------------------------------------------

-- 1. Lo que ya es un idioma admitido, escrito en su forma canonica ------------

UPDATE usuario
SET idioma = lower(idioma)
WHERE idioma IS NOT NULL
  AND lower(idioma) IN ('es', 'en', 'ca', 'gl', 'eu');

-- 2. Lo que no se reconoce pasa al idioma por defecto -------------------------

UPDATE usuario
SET idioma = 'es'
WHERE idioma IS NOT NULL
  AND idioma NOT IN ('es', 'en', 'ca', 'gl', 'eu');

-- 3. Y a partir de aqui la tabla no admite otra cosa --------------------------

ALTER TABLE usuario
    ADD CONSTRAINT usuario_idioma_check
        CHECK (idioma IN ('es', 'en', 'ca', 'gl', 'eu'));
