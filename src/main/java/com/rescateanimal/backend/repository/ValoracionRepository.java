package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Valoracion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Valoracion. Sin lógica de negocio.
 */
@Repository
public interface ValoracionRepository extends JpaRepository<Valoracion, Long> {
}
