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
import org.springframework.util.AntPathMatcher;

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
 *
 * Dentro de su espacio de nombres deniega por defecto. La distinción importa
 * porque el bróker no reparte comparando cadenas: DefaultSubscriptionRegistry
 * usa un AntPathMatcher, así que una suscripción a /topic/chat/* o a /topic/**
 * recibe lo que se publique en /topic/chat/42. Un destino que no case
 * exactamente con un id numérico se rechaza, en vez de dejarlo pasar por no
 * haberlo reconocido: reconocer solo lo esperado y aceptar todo lo demás es lo
 * que convierte un filtro en un colador.
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

    /** Espacio de nombres que este interceptor gobierna por completo. */
    private static final String PREFIJO_CHAT = "/topic/chat";

    /**
     * El mismo comparador que usa DefaultSubscriptionRegistry para decidir a qué
     * suscripciones entrega cada difusión. Se usa aquí para preguntarle lo mismo
     * que él se preguntará después: ¿este destino es un patrón?
     */
    private static final AntPathMatcher COMPARADOR = new AntPathMatcher();

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

        // Un destino con comodines no es un destino: es un patrón, y el bróker lo
        // trata como tal al repartir. Suscribirse a /topic/chat/* o a /topic/**
        // haría llegar TODAS las conversaciones a quien no participa en ninguna,
        // y ni siquiera pasaría por la comprobación de abajo, porque un patrón no
        // casa con DESTINO_CHAT. Como en esta API no hay ninguna suscripción
        // legítima con comodín —todos los destinos documentados en §13 son
        // literales—, se rechazan todos de plano en vez de intentar decidir
        // cuáles alcanzan un chat.
        if (COMPARADOR.isPattern(destino)) {
            log.warn("Suscripción rechazada al destino con comodín {}", destino);
            throw new MessagingException("No se admiten suscripciones con comodín");
        }

        Matcher coincidencia = DESTINO_CHAT.matcher(destino);
        if (!coincidencia.matches()) {
            // Denegar por defecto dentro del espacio de nombres del chat: si el
            // destino empieza por /topic/chat pero no es exactamente un id
            // numérico, se rechaza en vez de dejarlo pasar. Antes esta rama era
            // un "return mensaje" para cualquier destino que no casara, y de ahí
            // salía el agujero. Fuera de ese prefijo sí se deja pasar: /topic/mapa
            // y /user/queue/… se autorizan solo con estar autenticado, y eso ya
            // se resolvió en el handshake.
            if (destino.startsWith(PREFIJO_CHAT)) {
                log.warn("Suscripción rechazada al destino de chat mal formado {}", destino);
                throw new MessagingException("Destino de chat no válido");
            }
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
