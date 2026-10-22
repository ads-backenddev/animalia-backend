package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Notificacion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Notificacion. Sin lógica de negocio.
 */
@Repository
public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    /**
     * Las notificaciones de un usuario, en el orden que diga la paginación
     * (GET /api/notificaciones). Filtran siempre por destinatario: nadie lista
     * avisos ajenos, y no hay un listado general del que haya que excluirlos.
     * La consulta por (usuario_id, leida) la sirve el índice
     * idx_notificacion_usuario_leida de V1.
     */
    Page<Notificacion> findByUsuarioId(Long usuarioId, Pageable paginacion);

    /** Lo mismo, solo las leídas o solo las pendientes. */
    Page<Notificacion> findByUsuarioIdAndLeida(Long usuarioId, Boolean leida, Pageable paginacion);
}
