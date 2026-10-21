package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de Usuario. Sin lógica de negocio.
 *
 * JpaSpecificationExecutor lo necesita el listado del panel de administración
 * (RF-11), cuyos filtros —rol y activo— son opcionales y combinables; los
 * describe UsuarioAdminSpec.
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long>,
        JpaSpecificationExecutor<Usuario> {

    /** El email es la credencial de acceso: se usa en el login y al cargar el UserDetails. */
    Optional<Usuario> findByEmail(String email);

    /** Comprobación previa al registro, para responder 409 sin intentar el insert. */
    boolean existsByEmail(String email);

    /**
     * Usuarios activos de un área geográfica, excluyendo a uno: los destinatarios
     * de la notificación por alerta cercana (RF-05).
     *
     * El cruce es por texto porque el modelo del SRS define areaGeografica como
     * una cadena, no como un punto: no hay forma de calcular una distancia real
     * al usuario sin geocodificar antes ese campo.
     */
    List<Usuario> findByAreaGeograficaIgnoreCaseAndActivoTrueAndIdNot(String areaGeografica, Long usuarioId);

    /**
     * Los participantes de los chats abiertos por avistamientos de una alerta,
     * menos uno —su dueño—: los destinatarios del aviso ALERTA_ENCONTRADA.
     *
     * Un chat nace de un match positivo (interesado = true) y sus dos extremos
     * son quien deslizó y el autor del avistamiento deslizado, así que se
     * recogen los dos lados. Con el flujo del dueño, quien desliza es siempre
     * el dueño y los destinatarios son los testigos; recoger también a quien
     * deslizó cubre los matches que hicieron otros usuarios antes de que el
     * swipe quedara reservado al dueño. Los descartes no abren chat y no
     * cuentan.
     *
     * Cada usuario sale una sola vez aunque tenga varios chats sobre la misma
     * alerta, y solo las cuentas activas, como en el resto de avisos.
     *
     * El tipo va como parámetro, y no escrito en la consulta, por lo mismo que
     * en los feeds: publicacion_id es polimórfico y es el tipo lo que dice que
     * apunta a un avistamiento.
     */
    @Query("""
            SELECT u FROM Usuario u
            WHERE u.activo = true
              AND u.id <> :excluidoId
              AND (u.id IN (
                      SELECT m.usuario.id FROM MatchRegistro m, Avistamiento a
                      WHERE m.tipoPublicacion = :tipo
                        AND m.publicacionId = a.id
                        AND m.interesado = true
                        AND a.alerta.id = :alertaId)
                   OR u.id IN (
                      SELECT a.usuario.id FROM MatchRegistro m, Avistamiento a
                      WHERE m.tipoPublicacion = :tipo
                        AND m.publicacionId = a.id
                        AND m.interesado = true
                        AND a.alerta.id = :alertaId))
            """)
    List<Usuario> buscarParticipantesDeChatsDeLaAlerta(@Param("alertaId") Long alertaId,
                                                       @Param("excluidoId") Long excluidoId,
                                                       @Param("tipo") TipoPublicacion tipo);
}
