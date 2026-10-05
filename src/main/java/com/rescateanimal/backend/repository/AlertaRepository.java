package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de Alerta. Sin lógica de negocio: los umbrales (radio, ventana
 * temporal) llegan como parámetros desde AlertaService, que es donde se decide.
 */
@Repository
public interface AlertaRepository extends JpaRepository<Alerta, Long>, JpaSpecificationExecutor<Alerta> {

    /**
     * Listado filtrado de GET /api/alertas. Los criterios los describe AlertaSpec.
     *
     * Se sobrescribe solo para colgarle el grafo del autor, que cada tarjeta
     * enseña: sin él habría una consulta por fila (N+1). Es seguro al paginar
     * porque `usuario` es una asociación a-uno, igual que la protectora en
     * AnimalAdopcionRepository.
     */
    @Override
    @EntityGraph(attributePaths = "usuario")
    Page<Alerta> findAll(Specification<Alerta> filtros, Pageable paginacion);

    /**
     * La alerta con su fila bloqueada hasta el final de la transacción
     * (SELECT ... FOR UPDATE), para las operaciones que deciden según su estado
     * y después lo escriben: editar, retirar, cerrar y moderar.
     *
     * Sin el bloqueo, dos de ellas a la vez leían el mismo estado y la última en
     * confirmar ganaba: un PUT del dueño que leyó ACTIVA antes de que el
     * administrador confirmara la moderación escribía ACTIVA encima de
     * ELIMINADA, porque Hibernate actualiza todas las columnas. Con él, la
     * segunda espera a que la primera confirme y decide sobre el estado nuevo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Alerta a WHERE a.id = :id")
    Optional<Alerta> bloquearParaModificar(@Param("id") Long id);

    /**
     * Alertas activas y geolocalizadas, para pintar el mapa (RF-04).
     * Las que no tienen ubicación no pueden dibujarse, así que se excluyen.
     */
    List<Alerta> findByEstadoAndUltimaUbicacionIsNotNull(EstadoAlerta estado);

    /** Alertas publicadas por un usuario, en cualquier estado, más recientes primero. */
    List<Alerta> findByUsuarioIdOrderByCreatedAtDesc(Long usuarioId);

    /**
     * Alertas activas dentro de un radio, para el mapa acotado al área que el
     * usuario está viendo (RF-04). Evita mandar al móvil las alertas de todo el
     * país, que es lo que RNF-02 no puede permitirse con 100 usuarios a la vez.
     *
     * Mismo criterio de medida que buscarSimilar: geography, es decir metros
     * reales, y el índice GiST que crea la migración V1 es el que la sostiene.
     */
    @Query(value = """
            SELECT a.*
            FROM alerta a
            WHERE a.estado = 'ACTIVA'
              AND a.ultima_ubicacion IS NOT NULL
              AND ST_DWithin(
                    CAST(a.ultima_ubicacion AS geography),
                    CAST(ST_SetSRID(ST_MakePoint(:longitud, :latitud), 4326) AS geography),
                    :radioMetros)
            ORDER BY ST_Distance(
                    CAST(a.ultima_ubicacion AS geography),
                    CAST(ST_SetSRID(ST_MakePoint(:longitud, :latitud), 4326) AS geography))
            """, nativeQuery = true)
    List<Alerta> buscarActivasCerca(@Param("latitud") double latitud,
                                    @Param("longitud") double longitud,
                                    @Param("radioMetros") double radioMetros);

    /**
     * Busca una alerta que haga duplicada a la que se quiere publicar o editar
     * (RF-25): misma especie, dentro del radio indicado y PUBLICADA O AVISTADA
     * después de la fecha dada. Devuelve la más cercana.
     *
     * LAS DOS FECHAS, NO SOLO LA DEL AVISTAMIENTO. Con solo fecha_avistamiento,
     * una alerta publicada hoy sobre un animal visto hace dos días no bloqueaba
     * nunca a nadie —el caso más común: se pierde el sábado y se publica el
     * lunes—, y atrasar esa fecha al editar dejaba de proteger su sitio. created_at
     * lo pone la base de datos y no se edita, así que ninguna de las dos vías
     * sirve ya para esquivar la detección.
     *
     * excluirId deja fuera a la propia alerta cuando se comprueba una edición;
     * al publicar se pasa 0, que no es el id de ninguna (IDENTITY empieza en 1).
     *
     * Consulta nativa porque JPQL no conoce los operadores espaciales de PostGIS.
     * ST_DWithin sobre geography mide en metros reales sobre el elipsoide; con
     * geometry mediría en grados, que no sirve para un radio de 500 m.
     *
     * Se usa CAST(... AS geography) y no el operador ::geography porque Hibernate
     * interpreta los dos puntos como el inicio de un parámetro con nombre.
     *
     * "desde" es un Instant y fecha_avistamiento es TIMESTAMPTZ: la comparación
     * es entre dos puntos de la línea del tiempo, sin que intervenga la zona de
     * la JVM ni la de la sesión de PostgreSQL. Mientras la columna fue
     * "timestamp without time zone" esa comparación dependía de ambas.
     */
    @Query(value = """
            SELECT a.*
            FROM alerta a
            WHERE a.estado = 'ACTIVA'
              AND a.id <> :excluirId
              AND lower(a.especie) = lower(:especie)
              AND (a.created_at >= :desde OR a.fecha_avistamiento >= :desde)
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
                                   @Param("desde") Instant desde,
                                   @Param("excluirId") long excluirId);

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
