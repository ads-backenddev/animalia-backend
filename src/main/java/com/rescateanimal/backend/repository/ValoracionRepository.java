package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Valoracion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de Valoracion. Sin lógica de negocio.
 */
@Repository
public interface ValoracionRepository extends JpaRepository<Valoracion, Long> {

    /**
     * La valoración que un usuario ya emitió sobre una protectora, si la hay.
     *
     * Es lo que hace idempotente a PUT /api/protectoras/{id}/valoracion: volver
     * a valorar actualiza esta fila en vez de crear una segunda. La restricción
     * UNIQUE de V9 respalda la intención para el caso de dos peticiones
     * simultáneas, que esta consulta por sí sola no puede cubrir.
     */
    Optional<Valoracion> findByUsuarioIdAndProtectoraId(Long usuarioId, Long protectoraId);

    /**
     * Valoraciones recibidas por una protectora, más recientes primero (RF-10).
     *
     * El usuario viene con JOIN FETCH porque el panel pinta quién valoró; sin él
     * habría una consulta por valoración.
     */
    @Query("""
            SELECT v FROM Valoracion v
            JOIN FETCH v.usuario
            WHERE v.protectora.id = :protectoraId
            ORDER BY v.createdAt DESC
            """)
    List<Valoracion> buscarDeProtectoraConAutor(@Param("protectoraId") Long protectoraId);

    /**
     * Media de las puntuaciones de una protectora, o null si no tiene ninguna.
     *
     * Existe además del listado para que el resumen del panel no tenga que
     * traerse todas las valoraciones solo para promediarlas. Devuelve Double
     * —lo que da AVG— y no BigDecimal: el redondeo a la precisión que expone la
     * API lo decide el service, no la consulta.
     */
    @Query("SELECT AVG(v.puntuacion) FROM Valoracion v WHERE v.protectora.id = :protectoraId")
    Double mediaDeProtectora(@Param("protectoraId") Long protectoraId);

    /** Cuántas valoraciones tiene la protectora, para saber si la media es significativa. */
    long countByProtectoraId(Long protectoraId);
}
