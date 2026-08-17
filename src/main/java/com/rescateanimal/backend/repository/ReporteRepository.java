package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Reporte;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Reporte. Sin lógica de negocio.
 */
@Repository
public interface ReporteRepository extends JpaRepository<Reporte, Long> {
}
