package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de Protectora. Sin lógica de negocio.
 */
@Repository
public interface ProtectoraRepository extends JpaRepository<Protectora, Long> {

    /** El login necesita el estado de verificación de la protectora del usuario. */
    Optional<Protectora> findByUsuarioId(Long usuarioId);

    /**
     * Protectoras en un estado dado, con su cuenta ya cargada, más antiguas
     * primero (RF-11).
     *
     * Lo usa la cola de verificación del administrador. El orden es ascendente a
     * propósito, al revés que el resto de listados de la API: esto es una cola de
     * trabajo, y quien lleva más tiempo esperando es a quien toca atender —una
     * protectora que no puede operar hasta que alguien la mire—. Ordenarla por lo
     * más reciente dejaría a las primeras al final indefinidamente.
     *
     * El usuario viene con JOIN FETCH porque la respuesta muestra su nombre y su
     * email; sin él habría una consulta por protectora.
     */
    @Query("""
            SELECT p FROM Protectora p
            JOIN FETCH p.usuario
            WHERE p.estado = :estado
            ORDER BY p.createdAt ASC
            """)
    List<Protectora> buscarPorEstadoConCuenta(@Param("estado") EstadoProtectora estado);

    /**
     * Una protectora con su cuenta ya cargada.
     *
     * Aprobar o rechazar necesita al usuario para notificarle la decisión, y sin
     * el fetch ese acceso dispararía una consulta perezosa más.
     */
    @Query("""
            SELECT p FROM Protectora p
            JOIN FETCH p.usuario
            WHERE p.id = :id
            """)
    Optional<Protectora> buscarConCuenta(@Param("id") Long id);
}
