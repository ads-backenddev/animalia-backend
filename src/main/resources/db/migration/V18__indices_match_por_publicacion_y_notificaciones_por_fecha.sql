-- ---------------------------------------------------------------------------
-- V18 - Indices de MATCH_REGISTRO por publicacion y de NOTIFICACION por fecha
--
-- Los dos los pidio la revision del feed del dueno. Ninguna consulta va mal
-- hoy: con el punado de filas de desarrollo un recorrido secuencial gana a
-- cualquier indice (lo mismo que contaba V10). Son de volumen, y los dos
-- crecen con el uso normal. IndicesDeConsultaTest comprueba que existen y que
-- sirven a sus consultas.
--
-- idx_match_registro_publicacion (publicacion_id, tipo_publicacion). Al cerrar
-- una alerta, los destinatarios de ALERTA_ENCONTRADA se buscan saltando de los
-- avistamientos de la alerta a sus matches por publicacion y tipo
-- (UsuarioRepository.buscarParticipantesDeChatsDeLaAlerta). El unico indice de
-- la tabla aparte de la clave primaria, el de su restriccion unica, empieza por
-- usuario_id, y un B-tree compuesto solo se recorre por un prefijo de sus
-- columnas: sin este, cada cierre recorria MATCH_REGISTRO entera, que crece con
-- cada swipe, descartes incluidos. publicacion_id va primero porque es la que
-- distingue; el tipo solo tiene dos valores. Van siempre juntas porque la
-- referencia es polimorfica (ver MatchRegistro).
--
-- idx_notificacion_usuario_fecha (usuario_id, created_at DESC, id DESC).
-- GET /api/notificaciones ordena por created_at DESC, id DESC, y el indice de V1
-- es (usuario_id, leida): PostgreSQL filtraba con el y ordenaba en memoria
-- todas las notificaciones del usuario para devolver una sola pagina. Con este
-- las lee ya en el orden del listado y para en cuanto la tiene. El de V1 se
-- conserva: lo sigue usando el recuento de la pagina cuando se filtra por
-- leida.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_match_registro_publicacion ON match_registro (publicacion_id, tipo_publicacion);

CREATE INDEX idx_notificacion_usuario_fecha ON notificacion (usuario_id, created_at DESC, id DESC);
