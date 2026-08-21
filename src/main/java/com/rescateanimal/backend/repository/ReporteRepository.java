package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Reporte;
import com.rescateanimal.backend.model.enums.EstadoReporte;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Capa de datos de Reporte. Sin lógica de negocio.
 *
 * Todo lo de aquí es de lectura, y hoy siempre devuelve vacío: la tabla existe
 * desde la migración V1, pero nada puede insertar en ella hasta que el bloque 9
 * traiga ReporteService y el endpoint de reportar contenido (RF-26).
 */
@Repository
public interface ReporteRepository extends JpaRepository<Reporte, Long> {

    /**
     * Reportes en un estado dado, con su autor ya cargado, más antiguos primero
     * (RF-11).
     *
     * Ascendente por lo mismo que la cola de protectoras pendientes: es trabajo
     * por atender, y lo que lleva más tiempo esperando es lo que toca.
     */
    @Query("""
            SELECT r FROM Reporte r
            JOIN FETCH r.usuario
            WHERE r.estado = :estado
            ORDER BY r.createdAt ASC
            """)
    List<Reporte> buscarPorEstadoConAutor(@Param("estado") EstadoReporte estado);
}
