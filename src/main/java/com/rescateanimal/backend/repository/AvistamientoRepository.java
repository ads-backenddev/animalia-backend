package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
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
     * Avistamientos que el usuario todavía no ha deslizado, para el feed de
     * swipe (RF-15).
     *
     * Tres filtros, cada uno por un motivo distinto:
     * - la alerta debe seguir ACTIVA: si el animal ya apareció, el avistamiento
     *   ya no sirve para nada;
     * - se excluyen los propios, porque nadie hace match consigo mismo;
     * - y se excluye lo ya deslizado, en cualquiera de los dos sentidos, que es
     *   justo lo que la restricción única de MATCH_REGISTRO deja registrado.
     *
     * El NOT EXISTS se apoya en el índice único
     * (usuario_id, publicacion_id, tipo_publicacion), que cubre las tres
     * columnas del subconsulta.
     */
    @Query("""
            SELECT a FROM Avistamiento a
            JOIN FETCH a.alerta al
            JOIN FETCH a.usuario u
            WHERE al.estado = :estadoAlerta
              AND u.id <> :usuarioId
              AND NOT EXISTS (
                    SELECT 1 FROM MatchRegistro m
                    WHERE m.usuario.id = :usuarioId
                      AND m.tipoPublicacion = :tipoPublicacion
                      AND m.publicacionId = a.id)
            ORDER BY a.createdAt DESC
            """)
    List<Avistamiento> buscarCandidatosParaFeed(@Param("usuarioId") Long usuarioId,
                                                @Param("estadoAlerta") EstadoAlerta estadoAlerta,
                                                @Param("tipoPublicacion") TipoPublicacion tipoPublicacion,
                                                Pageable pageable);

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
}
