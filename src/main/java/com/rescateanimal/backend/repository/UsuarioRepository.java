package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Capa de datos de Usuario. Sin lógica de negocio: los métodos de consulta se
 * añadirán conforme los necesiten los services.
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
}
