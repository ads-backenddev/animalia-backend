package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Avistamiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Avistamiento. Sin lógica de negocio.
 */
@Repository
public interface AvistamientoRepository extends JpaRepository<Avistamiento, Long> {
}
