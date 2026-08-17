package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Alerta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Alerta. Sin lógica de negocio.
 * Aquí vivirán las consultas espaciales de PostGIS (mapa RF-04, duplicados RF-25).
 */
@Repository
public interface AlertaRepository extends JpaRepository<Alerta, Long> {
}
