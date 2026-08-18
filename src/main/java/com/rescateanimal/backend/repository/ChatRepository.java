package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de Chat. Sin lógica de negocio.
 *
 * Quiénes son los dos participantes de un chat no está en ninguna columna: se
 * deduce del match del que cuelga. Uno es quien deslizó (MATCH_REGISTRO.usuario)
 * y el otro quien publicó lo deslizado (AVISTAMIENTO.usuario). Ese segundo salto
 * no puede expresarse como asociación JPA porque publicacion_id es una
 * referencia polimórfica sin clave ajena, así que va escrito a mano en el WHERE.
 */
@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {

    /**
     * Chats de un conjunto de matches, en una sola consulta.
     *
     * Existe en plural para que el listado de /api/matches/mios no dispare una
     * consulta por match al rellenar el chatId (N+1).
     */
    List<Chat> findByMatchIdIn(Collection<Long> matchIds);

    /**
     * Chats en los que el usuario es uno de los dos extremos: o deslizó, o es el
     * autor del avistamiento deslizado (RF-12).
     *
     * El match viene ya cargado —y con él su usuario— porque el listado los
     * necesita todos para resolver el otro participante de cada fila; sin el
     * JOIN FETCH cada chat dispararía dos consultas perezosas más.
     */
    @Query("""
            SELECT c FROM Chat c
            JOIN FETCH c.match m
            JOIN FETCH m.usuario
            WHERE m.tipoPublicacion = :tipoPublicacion
              AND (m.usuario.id = :usuarioId
                   OR EXISTS (SELECT 1 FROM Avistamiento a
                              WHERE a.id = m.publicacionId
                                AND a.usuario.id = :usuarioId))
            """)
    List<Chat> buscarPorParticipante(@Param("usuarioId") Long usuarioId,
                                     @Param("tipoPublicacion") TipoPublicacion tipoPublicacion);

    /**
     * Un chat con su match y el autor del deslizamiento ya cargados.
     *
     * No filtra por participante a propósito: quién puede verlo lo decide
     * ChatService, que necesita distinguir "no existe" (404) de "no es tuyo"
     * (403). Una consulta que ya filtrara los devolvería iguales.
     */
    @Query("""
            SELECT c FROM Chat c
            JOIN FETCH c.match m
            JOIN FETCH m.usuario
            WHERE c.id = :chatId
            """)
    Optional<Chat> buscarConMatch(@Param("chatId") Long chatId);
}
