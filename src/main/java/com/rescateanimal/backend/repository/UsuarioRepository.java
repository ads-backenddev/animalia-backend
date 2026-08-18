package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Capa de datos de Usuario. Sin lógica de negocio: solo consultas derivadas.
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    /** El email es la credencial de acceso: se usa en el login y al cargar el UserDetails. */
    Optional<Usuario> findByEmail(String email);

    /** Comprobación previa al registro, para responder 409 sin intentar el insert. */
    boolean existsByEmail(String email);
}
