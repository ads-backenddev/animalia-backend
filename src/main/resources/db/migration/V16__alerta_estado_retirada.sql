-- ---------------------------------------------------------------------------
-- V16 - ALERTA.estado admite RETIRADA: la retira su dueno
--
-- Hasta ahora una alerta solo salia de circulacion de dos maneras: ENCONTRADA,
-- cuando aparece el animal (RF-14), y ELIMINADA, cuando la retira el
-- administrador por incumplir las normas (RF-11). DELETE /api/alertas/{id}, con
-- el que la retira su propio dueno, estaba disenado pero nunca se construyo.
--
-- POR QUE UN VALOR NUEVO Y NO REUTILIZAR ELIMINADA. Son dos hechos distintos:
-- una sancion y una decision neutra del dueno. Si las dos escribieran
-- ELIMINADA, la diferencia se perderia en cada fila y no habria forma de
-- reconstruirla despues, y sin ella no hay estadisticas de moderacion, ni
-- reincidencia, ni recurso contra una retirada. Separarlas solo es posible
-- ahora: como el DELETE del dueno no existia, TODAS las ELIMINADA actuales son
-- del administrador y no hay que migrar ningun dato.
--
-- No acompana a ningun cambio de columna: solo el valor nuevo en el CHECK, que
-- ddl-auto=validate no comprueba y que, sin esta migracion, haria fallar en
-- ejecucion el primer UPDATE a RETIRADA.
-- ---------------------------------------------------------------------------

ALTER TABLE alerta DROP CONSTRAINT alerta_estado_check;

ALTER TABLE alerta ADD CONSTRAINT alerta_estado_check
    CHECK (estado IN ('ACTIVA', 'ENCONTRADA', 'RETIRADA', 'ELIMINADA'));
