package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.MatchRegistro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de MatchRegistro. Sin lógica de negocio.
 */
@Repository
public interface MatchRegistroRepository extends JpaRepository<MatchRegistro, Long> {
}
