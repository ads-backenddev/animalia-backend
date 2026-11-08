-- ---------------------------------------------------------------------------
-- V17 - Tipos de notificacion del flujo del dueno de la alerta
--
-- El feed de avistamientos pasa a ser del dueno de la alerta: es el quien
-- revisa los avistamientos de SU alerta y decide si es su animal. Para que se
-- entere de que hay algo que revisar, y para que los testigos sepan que el
-- animal ha aparecido, hacen falta dos avisos que no tenian tipo:
--
--   NUEVO_AVISTAMIENTO  al dueno, cuando alguien registra un avistamiento en
--                       su alerta. referencia_id es la ALERTA, no el
--                       avistamiento: el aviso abre el feed de esa alerta.
--   ALERTA_ENCONTRADA   a quienes tienen un chat abierto por un avistamiento
--                       de la alerta, cuando el dueno la cierra como
--                       ENCONTRADA. referencia_id es la alerta.
--
-- Mismo procedimiento que V6, y por el mismo motivo: notificacion.tipo es un
-- varchar con un CHECK que lista los valores, PostgreSQL no sabe ampliar un
-- CHECK en el sitio y validate no lo compara. Sin esta migracion el primer
-- aviso de un tipo nuevo fallaria al insertarse. Se repiten los nueve tipos
-- anteriores para que la lista siga siendo la definicion completa.
--
-- El ancho de la columna no cambia: NUEVO_AVISTAMIENTO, el mas largo, son 18
-- caracteres y la columna admite 30.
-- ---------------------------------------------------------------------------

ALTER TABLE notificacion DROP CONSTRAINT notificacion_tipo_check;

ALTER TABLE notificacion ADD CONSTRAINT notificacion_tipo_check CHECK (tipo IN (
    'ALERTA_CERCANA', 'NUEVO_MATCH', 'NUEVO_MENSAJE',
    'PROTECTORA_VERIFICADA', 'ADOPCION_CONFIRMADA', 'REPORTE_RESUELTO',
    'PROTECTORA_RECHAZADA', 'CUENTA_BLOQUEADA', 'CUENTA_DESBLOQUEADA',
    'NUEVO_AVISTAMIENTO', 'ALERTA_ENCONTRADA'));
