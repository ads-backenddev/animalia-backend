package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Mensaje;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Capa de datos de Mensaje. Sin lógica de negocio: quién puede leer un chat lo
 * decide ChatService, aquí solo se expresan las consultas.
 */
@Repository
public interface MensajeRepository extends JpaRepository<Mensaje, Long> {

    /** Número de mensajes sin leer de una conversación. */
    interface ConteoNoLeidos {
        Long getChatId();

        long getTotal();
    }

    /**
     * Histórico de un chat, paginado (RF-12).
     *
     * El emisor viene cargado porque cada mensaje se pinta con el nombre y la
     * foto de quien lo escribió; sin el JOIN FETCH una página de 20 mensajes
     * dispararía hasta 20 consultas más. Es un fetch ToOne, así que la
     * paginación la sigue resolviendo la base de datos y no la memoria.
     *
     * La countQuery se declara aparte porque Spring Data no puede derivarla de
     * una consulta con JOIN FETCH.
     */
    @Query(value = """
            SELECT m FROM Mensaje m
            JOIN FETCH m.emisor
            WHERE m.chat.id = :chatId
            """,
            countQuery = "SELECT COUNT(m) FROM Mensaje m WHERE m.chat.id = :chatId")
    Page<Mensaje> buscarPorChat(@Param("chatId") Long chatId, Pageable pageable);

    /**
     * Último mensaje de cada uno de esos chats, para la fila del listado de
     * conversaciones.
     *
     * Se identifica por MAX(id) y no por MAX(created_at): el id es estrictamente
     * creciente, así que señala una única fila, mientras que dos mensajes del
     * mismo chat con la misma marca de tiempo devolverían dos "últimos".
     */
    @Query("""
            SELECT m FROM Mensaje m
            JOIN FETCH m.emisor
            WHERE m.id IN (SELECT MAX(ultimo.id) FROM Mensaje ultimo
                           WHERE ultimo.chat.id IN :chatIds
                           GROUP BY ultimo.chat.id)
            """)
    List<Mensaje> buscarUltimoDeCadaChat(@Param("chatIds") Collection<Long> chatIds);

    /**
     * Cuántos mensajes del otro participante quedan sin leer en cada chat.
     *
     * Los chats sin ninguno simplemente no aparecen en el resultado: el GROUP BY
     * no genera filas con cero.
     */
    @Query("""
            SELECT m.chat.id AS chatId, COUNT(m) AS total
            FROM Mensaje m
            WHERE m.chat.id IN :chatIds
              AND m.emisor.id <> :usuarioId
              AND m.leido = false
            GROUP BY m.chat.id
            """)
    List<ConteoNoLeidos> contarNoLeidosPorChat(@Param("chatIds") Collection<Long> chatIds,
                                               @Param("usuarioId") Long usuarioId);

    /**
     * Marca como leídos los mensajes que el usuario ha recibido en ese chat.
     *
     * El filtro emisor.id <> :usuarioId es la regla entera: nunca se tocan los
     * mensajes propios, porque "leído" significa que lo leyó el destinatario y
     * el emisor no es destinatario de sí mismo. Va en un UPDATE masivo en vez de
     * cargar y modificar cada entidad porque no hace falta traerse a memoria una
     * conversación entera para cambiarle un booleano.
     *
     * Sin clearAutomatically, por el mismo motivo que en
     * LimiteMatchDiarioRepository: vaciar el contexto de persistencia
     * desprendería el chat y el match que el service ya tiene cargados para
     * comprobar los permisos. A cambio, cualquier Mensaje que estuviera cargado
     * en esta misma transacción se quedaría con el valor viejo de "leido"; no
     * ocurre, porque el único camino hasta aquí no carga mensajes.
     *
     * @return cuántos ha marcado
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Mensaje m SET m.leido = true
            WHERE m.chat.id = :chatId
              AND m.emisor.id <> :usuarioId
              AND m.leido = false
            """)
    int marcarRecibidosComoLeidos(@Param("chatId") Long chatId,
                                  @Param("usuarioId") Long usuarioId);
}
