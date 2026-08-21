package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Valoracion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Capa de datos de Valoracion. Sin lógica de negocio.
 *
 * Todo lo de aquí es de lectura, y hoy siempre devuelve vacío: la tabla existe
 * desde la migración V1, pero nada puede insertar en ella hasta que el bloque 9
 * traiga ValoracionService y el endpoint de valorar (RF-23).
 */
@Repository
public interface ValoracionRepository extends JpaRepository<Valoracion, Long> {

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
