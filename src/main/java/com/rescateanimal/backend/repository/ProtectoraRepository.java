package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Protectora;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Capa de datos de Protectora. Sin lógica de negocio.
 */
@Repository
public interface ProtectoraRepository extends JpaRepository<Protectora, Long> {

    /** El login necesita el estado de verificación de la protectora del usuario. */
    Optional<Protectora> findByUsuarioId(Long usuarioId);
}
