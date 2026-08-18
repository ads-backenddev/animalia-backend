package com.rescateanimal.backend.security;

import com.rescateanimal.backend.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autoriza las suscripciones a /topic/chat/{chatId} (RF-12).
 *
 * Un destino STOMP no tiene dueño: cualquier sesión autenticada puede pedir
 * suscribirse a cualquier destino, y los ids de chat son secuenciales, así que
 * sin esta comprobación bastaría con probar números para quedarse escuchando
 * conversaciones ajenas en tiempo real. El handshake solo responde "quién eres",
 * no "a qué tienes derecho"; eso se decide aquí, al suscribirse.
 *
 * Va en el canal de entrada del cliente y no en la seguridad HTTP porque, una
 * vez abierta la conexión, los SUBSCRIBE ya no son peticiones HTTP y no pasan
 * por SecurityConfig ni por @PreAuthorize.
 *
 * Solo gobierna los destinos de chat: cualquier otro —/topic/mapa,
 * /user/queue/…— lo deja pasar tal cual, porque su autorización es simplemente
 * estar autenticado, que ya se resolvió en el handshake.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSuscripcionInterceptor implements ChannelInterceptor {

    /**
     * El límite de dígitos evita que un destino con un número descomunal
     * reviente el parseo: no cabría en un long y el interceptor fallaría con una
     * excepción de formato en vez de con un rechazo limpio.
     */
    private static final Pattern DESTINO_CHAT = Pattern.compile("^/topic/chat/(\\d{1,18})$");

    private final ChatService chatService;

    @Override
    public Message<?> preSend(Message<?> mensaje, MessageChannel canal) {
        StompHeaderAccessor accesor =
                MessageHeaderAccessor.getAccessor(mensaje, StompHeaderAccessor.class);

        if (accesor == null || StompCommand.SUBSCRIBE != accesor.getCommand()) {
            return mensaje;
        }

        String destino = accesor.getDestination();
        if (destino == null) {
            return mensaje;
        }

        Matcher coincidencia = DESTINO_CHAT.matcher(destino);
        if (!coincidencia.matches()) {
            return mensaje;
        }

        UsuarioPrincipal usuario = UsuarioPrincipal.desde(accesor.getUser());
        if (usuario == null) {
            throw new MessagingException("No se puede suscribir a un chat sin sesión autenticada");
        }

        long chatId = Long.parseLong(coincidencia.group(1));
        if (!chatService.esParticipante(chatId, usuario.getId())) {
            // Un chat inexistente y uno ajeno se rechazan igual: en STOMP no hay
            // códigos de estado con los que distinguirlos, y hacerlo convertiría
            // el canal en un detector de qué ids existen.
            log.warn("El usuario {} ha intentado suscribirse al chat {}, del que no es participante",
                    usuario.getId(), chatId);
            throw new MessagingException("No participas en esta conversación");
        }

        log.debug("Suscripción al chat {} autorizada para el usuario {}", chatId, usuario.getId());
        return mensaje;
    }
}
