package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Notificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Notificacion. Sin lógica de negocio.
 */
@Repository
public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {
}
