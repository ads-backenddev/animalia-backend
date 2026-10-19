package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.EnviarMensajeRequest;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.model.Mensaje;
import com.rescateanimal.backend.repository.MensajeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Envío de mensajes y marcado de leídos (RF-12).
 *
 * Hay dos vías de entrada —POST /api/chats/{id}/mensajes y el destino STOMP
 * /app/chat/{chatId}/enviar— y una sola implementación: las dos acaban aquí, así
 * que un mensaje se guarda y se difunde igual venga por donde venga. Si la
 * difusión viviera en el controller WebSocket (con @SendTo, por ejemplo), un
 * mensaje enviado por REST no le llegaría en vivo al otro participante, que es
 * justo lo que la vía REST tiene que seguir cumpliendo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MensajeService {

    /** Destino de difusión de una conversación; el {0} es el id del chat. */
    public static final String CANAL_CHAT = "/topic/chat/";

    private final MensajeRepository mensajeRepository;
    private final ChatService chatService;
    private final SimpMessagingTemplate mensajeria;

    /**
     * Guarda un mensaje y lo entrega a los dos participantes conectados.
     *
     * TRANSACCIÓN Y DIFUSIÓN. El mensaje se persiste dentro de la transacción y
     * se difunde DESPUÉS de confirmarla, igual que los eventos del mapa. El caso
     * no es equiparable al del match: allí la notificación comparte transacción
     * porque también es una escritura en la base de datos, y dos escrituras sí
     * pueden ser atómicas entre sí. Una difusión por WebSocket no lo es —es un
     * envío por un socket, y ningún rollback lo retira—, así que lo único que se
     * puede elegir es cuándo sale.
     *
     * Sale después de confirmar por lo que pasaría al revés: si se emitiera
     * dentro de la transacción y esta terminara en rollback, el otro
     * participante tendría en pantalla un mensaje que no existe en el histórico
     * y que desaparecería al recargar. En un chat eso es peor que en el mapa: lo
     * que se le enseñó fue una frase que su interlocutor nunca dijo.
     *
     * Y un fallo de la mensajería no tumba la operación, porque a esas alturas
     * el mensaje ya está confirmado: devolver un error haría que el cliente lo
     * reenviara y acabara duplicado. La entrega no se pierde, solo se retrasa
     * hasta que el destinatario vuelva a pedir el histórico o se reconecte, que
     * es exactamente para lo que existe GET /api/chats/{id}/mensajes.
     */
    @Transactional
    public MensajeResponse enviar(Long chatId, Long emisorId, EnviarMensajeRequest peticion) {
        ChatService.Participantes participantes = chatService.exigirParticipante(chatId, emisorId);

        Mensaje mensaje = new Mensaje();
        mensaje.setChat(participantes.chat());
        mensaje.setEmisor(participantes.yo(emisorId));
        mensaje.setContenido(peticion.getContenido().trim());
        mensaje.setLeido(false);

        Mensaje guardado = mensajeRepository.save(mensaje);

        // El DTO se construye ahora, con la entidad todavía gestionada, y no
        // dentro de la retrollamada, donde el contexto de persistencia ya se
        // está cerrando y los accesos perezosos fallarían.
        MensajeResponse respuesta = ChatService.aMensajeResponse(guardado);
        difundirTrasConfirmar(chatId, respuesta);

        log.debug("Mensaje {} enviado por el usuario {} en el chat {}",
                guardado.getId(), emisorId, chatId);
        return respuesta;
    }

    /**
     * Marca como leídos los mensajes recibidos en la conversación (RF-12).
     *
     * Solo los del otro participante: los propios no se tocan nunca, porque
     * "leído" describe que el destinatario lo vio y nadie es destinatario de sí
     * mismo. La regla la aplica el WHERE de la consulta, no un filtro posterior,
     * para que no dependa de qué se cargue en memoria.
     *
     * @return cuántos mensajes ha marcado
     */
    @Transactional
    public int marcarLeidos(Long chatId, Long usuarioId) {
        chatService.exigirParticipante(chatId, usuarioId);

        int marcados = mensajeRepository.marcarRecibidosComoLeidos(chatId, usuarioId);
        log.debug("Chat {}: el usuario {} ha marcado {} mensajes como leídos",
                chatId, usuarioId, marcados);
        return marcados;
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private void difundirTrasConfirmar(Long chatId, MensajeResponse mensaje) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    difundir(chatId, mensaje);
                }
            });
        } else {
            difundir(chatId, mensaje);
        }
    }

    /**
     * Entrega el mensaje a quien esté suscrito a la conversación.
     *
     * Que solo lo reciban los dos participantes no lo decide este método: el
     * destino /topic/chat/{chatId} es público entre autenticados y quien filtra
     * es ChatSuscripcionInterceptor, que rechaza la suscripción de cualquiera
     * que no participe.
     */
    private void difundir(Long chatId, MensajeResponse mensaje) {
        try {
            mensajeria.convertAndSend(CANAL_CHAT + chatId, mensaje);
        } catch (RuntimeException e) {
            log.error("No se pudo difundir el mensaje {} en {}{}",
                    mensaje.getId(), CANAL_CHAT, chatId, e);
        }
    }
}
