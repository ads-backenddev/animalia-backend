package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de Alerta. Sin lógica de negocio: los umbrales (radio, ventana
 * temporal) llegan como parámetros desde AlertaService, que es donde se decide.
 */
@Repository
public interface AlertaRepository extends JpaRepository<Alerta, Long> {

    /**
     * Alertas activas y geolocalizadas, para pintar el mapa (RF-04).
     * Las que no tienen ubicación no pueden dibujarse, así que se excluyen.
     */
    List<Alerta> findByEstadoAndUltimaUbicacionIsNotNull(EstadoAlerta estado);

    /** Alertas publicadas por un usuario, en cualquier estado, más recientes primero. */
    List<Alerta> findByUsuarioIdOrderByCreatedAtDesc(Long usuarioId);

    /**
     * Busca una alerta que haga duplicada a la que se quiere publicar (RF-25):
     * misma especie, dentro del radio indicado y con avistamiento posterior a la
     * fecha dada. Devuelve la más cercana.
     *
     * Consulta nativa porque JPQL no conoce los operadores espaciales de PostGIS.
     * ST_DWithin sobre geography mide en metros reales sobre el elipsoide; con
     * geometry mediría en grados, que no sirve para un radio de 500 m.
     *
     * Se usa CAST(... AS geography) y no el operador ::geography porque Hibernate
     * interpreta los dos puntos como el inicio de un parámetro con nombre.
     */
    @Query(value = """
            SELECT a.*
            FROM alerta a
            WHERE a.estado = 'ACTIVA'
              AND lower(a.especie) = lower(:especie)
              AND a.fecha_avistamiento >= :desde
              AND a.ultima_ubicacion IS NOT NULL
              AND ST_DWithin(
                    CAST(a.ultima_ubicacion AS geography),
                    CAST(ST_SetSRID(ST_MakePoint(:longitud, :latitud), 4326) AS geography),
                    :radioMetros)
            ORDER BY ST_Distance(
                    CAST(a.ultima_ubicacion AS geography),
                    CAST(ST_SetSRID(ST_MakePoint(:longitud, :latitud), 4326) AS geography))
            LIMIT 1
            """, nativeQuery = true)
    Optional<Alerta> buscarSimilar(@Param("especie") String especie,
                                   @Param("latitud") double latitud,
                                   @Param("longitud") double longitud,
                                   @Param("radioMetros") double radioMetros,
                                   @Param("desde") LocalDateTime desde);

    /**
     * Distancia en metros entre una alerta y unas coordenadas. Se consulta a
     * PostGIS en lugar de calcularla en Java para que sea la misma medida que usó
     * ST_DWithin al decidir que era un duplicado.
     */
    @Query(value = """
            SELECT ST_Distance(
                    CAST(a.ultima_ubicacion AS geography),
                    CAST(ST_SetSRID(ST_MakePoint(:longitud, :latitud), 4326) AS geography))
            FROM alerta a
            WHERE a.id = :alertaId
            """, nativeQuery = true)
    Double distanciaMetros(@Param("alertaId") Long alertaId,
                           @Param("latitud") double latitud,
                           @Param("longitud") double longitud);
}
