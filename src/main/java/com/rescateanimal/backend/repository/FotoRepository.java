package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Foto;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso al registro de fotos subidas.
 *
 * Las consultas de uso —si algún foto_url apunta a una foto— y las de la
 * limpieza diaria llegan con las fases que las necesitan.
 */
public interface FotoRepository extends JpaRepository<Foto, String> {

    /**
     * ¿Es esta foto de este usuario? Es la comprobación de propiedad de cada
     * fotoUrl recibido, y va por la clave primaria.
     */
    boolean existsByNombreAndUsuarioId(String nombre, Long usuarioId);
}
