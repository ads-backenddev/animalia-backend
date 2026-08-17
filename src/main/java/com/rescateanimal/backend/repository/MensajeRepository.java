package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Mensaje;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Mensaje. Sin lógica de negocio.
 */
@Repository
public interface MensajeRepository extends JpaRepository<Mensaje, Long> {
}
