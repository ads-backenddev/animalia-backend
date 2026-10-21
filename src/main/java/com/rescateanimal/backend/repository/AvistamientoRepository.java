package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Capa de datos de Avistamiento. Sin lógica de negocio: qué se considera
 * candidato lo decide AvistamientoService, aquí solo se expresa la consulta.
 */
@Repository
public interface AvistamientoRepository extends JpaRepository<Avistamiento, Long> {

    /**
     * Avistamientos de UNA alerta que su dueño todavía no ha revisado, para su
     * feed de swipe.
     *
     * Tres filtros, cada uno por su motivo:
     * - el avistamiento debe estar VISIBLE: uno retirado por el administrador
     *   (RF-11) no se ofrece. Se compara con VISIBLE en lugar de excluir
     *   ELIMINADO, como el catálogo de adopción con DISPONIBLE, para que
     *   cualquier estado que se añada al enum nazca fuera del feed;
     * - se excluyen los que registró el propio dueño, porque no hay nada que
     *   reconocer en lo que uno mismo vio;
     * - y se excluye lo que el dueño ya deslizó, en cualquiera de los dos
     *   sentidos. Lo que deslicen otros no cuenta: revisar es cosa del dueño.
     *   El NOT EXISTS se apoya en el índice único (usuario_id, publicacion_id,
     *   tipo_publicacion) de MATCH_REGISTRO, que cubre sus tres columnas.
     *
     * El estado de la alerta no se filtra aquí: quien llama ya la ha leído para
     * comprobar que es del usuario y que sigue ACTIVA.
     *
     * Más recientes primero, con el id como desempate para que dos del mismo
     * instante no cambien de orden entre llamadas. El autor viene con JOIN FETCH
     * porque cada tarjeta lo enseña; la alerta no hace falta, ya está cargada.
     */
    @Query("""
            SELECT a FROM Avistamiento a
            JOIN FETCH a.usuario u
            WHERE a.alerta.id = :alertaId
              AND a.estado = :estadoVisible
              AND u.id <> :duenoId
              AND NOT EXISTS (
                    SELECT 1 FROM MatchRegistro m
                    WHERE m.usuario.id = :duenoId
                      AND m.tipoPublicacion = :tipoPublicacion
                      AND m.publicacionId = a.id)
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<Avistamiento> buscarPendientesDeRevisar(@Param("alertaId") Long alertaId,
                                                 @Param("duenoId") Long duenoId,
                                                 @Param("estadoVisible") EstadoAvistamiento estadoVisible,
                                                 @Param("tipoPublicacion") TipoPublicacion tipoPublicacion,
                                                 Pageable pageable);

    /**
     * Distancia en metros entre cada avistamiento y la última ubicación de su
     * alerta, para las tarjetas del feed.
     *
     * Consulta aparte, y nativa, porque JPQL no puede escribir el CAST a
     * geography, y sin él ST_Distance mediría en grados. Es la misma medida que
     * AlertaRepository.buscarSimilar. Recibe una colección para resolver la
     * tanda entera de una vez y no una consulta por tarjeta.
     *
     * Los avistamientos sin ubicación, o de una alerta sin ella, no salen: quien
     * llama los busca en un mapa y no encontrarlos significa "sin distancia".
     */
    @Query(value = """
            SELECT av.id AS avistamientoId,
                   ST_Distance(
                       CAST(av.ubicacion AS geography),
                       CAST(al.ultima_ubicacion AS geography)) AS metros
            FROM avistamiento av
            JOIN alerta al ON al.id = av.alerta_id
            WHERE av.id IN (:ids)
              AND av.ubicacion IS NOT NULL
              AND al.ultima_ubicacion IS NOT NULL
            """, nativeQuery = true)
    List<DistanciaALaAlerta> distanciasALaAlerta(@Param("ids") Collection<Long> ids);

    /** Una fila de distanciasALaAlerta. */
    interface DistanciaALaAlerta {
        Long getAvistamientoId();

        Double getMetros();
    }

    /**
     * Avistamientos con su autor y su alerta ya cargados, buscados por id.
     *
     * Lo usa el chat (RF-12): el otro participante de una conversación es el
     * autor del avistamiento deslizado, y la cabecera del chat muestra además de
     * qué alerta colgaba. Recibe una colección para que el listado de chats
     * resuelva todos sus avistamientos de una vez y no uno por conversación
     * (N+1); MATCH_REGISTRO.publicacion_id no es una clave ajena, así que no hay
     * asociación que pudiera traerlos con un JOIN FETCH desde el chat.
     */
    @Query("""
            SELECT a FROM Avistamiento a
            JOIN FETCH a.usuario
            JOIN FETCH a.alerta
            WHERE a.id IN :ids
            """)
    List<Avistamiento> buscarConAutorYAlerta(@Param("ids") Collection<Long> ids);

    /**
     * Los avistamientos de una alerta en un estado, con su autor ya cargado,
     * para el detalle de la alerta (GET /api/alertas/{id}). Más recientes
     * primero: lo último que se sabe del animal es lo que más importa.
     */
    @Query("""
            SELECT a FROM Avistamiento a
            JOIN FETCH a.usuario
            WHERE a.alerta.id = :alertaId
              AND a.estado = :estado
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<Avistamiento> buscarDeLaAlerta(@Param("alertaId") Long alertaId,
                                        @Param("estado") EstadoAvistamiento estado);
}
