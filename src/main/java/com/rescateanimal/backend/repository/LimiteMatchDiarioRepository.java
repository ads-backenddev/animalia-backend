package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.LimiteMatchDiario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de LimiteMatchDiario. Sin lógica de negocio.
 */
@Repository
public interface LimiteMatchDiarioRepository extends JpaRepository<LimiteMatchDiario, Long> {
}
