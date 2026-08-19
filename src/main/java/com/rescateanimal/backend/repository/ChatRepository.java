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
 * y el otro quien publicó lo deslizado: AVISTAMIENTO.usuario si el match es
 * sobre un avistamiento (RF-08), o ANIMAL_ADOPCION.protectora.usuario si es
 * sobre un animal en adopción (RF-09). Ese segundo salto no puede expresarse
 * como asociación JPA porque publicacion_id es una referencia polimórfica sin
 * clave ajena, así que va escrito a mano en el WHERE, siempre emparejado con el
 * tipo que dice a qué tabla apunta.
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
     * Chats en los que el usuario es uno de los dos extremos: o deslizó, o
     * publicó lo deslizado (RF-12).
     *
     * "Publicó lo deslizado" significa una cosa distinta según el tipo, y por
     * eso hay dos EXISTS y no uno: en un avistamiento es su autor
     * (AVISTAMIENTO.usuario) y en una adopción es la cuenta de la protectora
     * dueña del animal (ANIMAL_ADOPCION.protectora.usuario). Cada rama va
     * emparejada con su tipo para que publicacion_id no se lea nunca contra la
     * tabla equivocada: es una referencia polimórfica, así que el id 7 existe
     * como avistamiento y como animal, y sin el filtro de tipo un usuario vería
     * chats ajenos por pura colisión de ids.
     *
     * El match viene ya cargado —y con él su usuario— porque el listado los
     * necesita todos para resolver el otro participante de cada fila; sin el
     * JOIN FETCH cada chat dispararía dos consultas perezosas más.
     */
    @Query("""
            SELECT c FROM Chat c
            JOIN FETCH c.match m
            JOIN FETCH m.usuario
            WHERE m.usuario.id = :usuarioId
               OR (m.tipoPublicacion = :avistamiento
                   AND EXISTS (SELECT 1 FROM Avistamiento a
                               WHERE a.id = m.publicacionId
                                 AND a.usuario.id = :usuarioId))
               OR (m.tipoPublicacion = :adopcion
                   AND EXISTS (SELECT 1 FROM AnimalAdopcion an
                               WHERE an.id = m.publicacionId
                                 AND an.protectora.usuario.id = :usuarioId))
            """)
    List<Chat> buscarPorParticipante(@Param("usuarioId") Long usuarioId,
                                     @Param("avistamiento") TipoPublicacion avistamiento,
                                     @Param("adopcion") TipoPublicacion adopcion);

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
