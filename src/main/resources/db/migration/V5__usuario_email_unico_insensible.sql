-- ===========================================================================
-- V5 - La unicidad del email deja de distinguir mayusculas.
--
-- EL HUECO. La aplicacion trata los emails como insensibles a mayusculas:
-- AuthService.normalizarEmail pasa a minusculas antes de registrar y antes de
-- buscar. La base de datos, en cambio, tenia un UNIQUE sobre la columna en
-- crudo, que considera "Ana@Test.es" y "ana@test.es" dos valores distintos. Es
-- decir, la regla existia en un solo sitio y ese sitio era codigo: cualquier
-- alta que no pasara por ese metodo —una carga de datos, una migracion futura,
-- un endpoint nuevo que se olvidara de normalizar— podia dejar dos cuentas para
-- la misma persona, y a partir de ahi findByEmail devolveria una u otra sin
-- criterio.
--
-- POR QUE SE SUSTITUYE Y NO SE SUMA. UNIQUE (lower(email)) implica
-- UNIQUE (email): si dos emails son iguales letra a letra, tambien lo son en
-- minusculas, asi que el funcional rechaza todo lo que rechazaba el anterior y
-- ademas lo que se le escapaba. Dejar los dos seria pagar dos indices unicos en
-- cada alta de usuario para una garantia que ya da uno.
--
-- Se aprovecha para ponerle nombre: el que tenia, uk5171l57faosmj8myawaucatdw,
-- lo invento Hibernate y no dice nada al leer un error de clave duplicada.
--
-- SI ESTA MIGRACION FALLA con "could not create unique index", la base de datos
-- ya tiene dos usuarios cuyo email solo se diferencia en mayusculas. No los
-- borra por su cuenta a proposito: decidir cual de las dos cuentas sobrevive no
-- es algo que deba hacer un script en silencio.
-- ===========================================================================

ALTER TABLE usuario
    DROP CONSTRAINT uk5171l57faosmj8myawaucatdw;

CREATE UNIQUE INDEX uk_usuario_email_lower
    ON usuario USING btree (lower(email));
