package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Foto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acceso al registro de fotos subidas.
 */
public interface FotoRepository extends JpaRepository<Foto, String> {

    /**
     * ¿Es esta foto de este usuario? Es la comprobación de propiedad de cada
     * fotoUrl recibido, y va por la clave primaria.
     */
    boolean existsByNombreAndUsuarioId(String nombre, Long usuarioId);

    /**
     * ¿Usa esta URL alguna publicación —alerta, avistamiento o animal en
     * adopción—? El perfil no entra: es justo de donde se está quitando.
     *
     * Una sola consulta con tres EXISTS, que se cortan en la primera fila que
     * encuentran. Ninguna de las tres columnas foto_url tiene índice, así que
     * cada EXISTS que no encuentra nada recorre su tabla; se acepta porque solo
     * se pregunta al cambiar una foto de perfil o al dar de baja una cuenta.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM alerta WHERE foto_url = :url)
                OR EXISTS (SELECT 1 FROM avistamiento WHERE foto_url = :url)
                OR EXISTS (SELECT 1 FROM animal_adopcion WHERE foto_url = :url)
            """, nativeQuery = true)
    boolean usadaEnPublicaciones(@Param("url") String url);
}
