package com.rescateanimal.backend.security;

import com.rescateanimal.backend.service.ChatService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.time.Instant;
import java.util.Map;
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
 * HACE DOS COSAS, y conviene distinguirlas porque tienen alcances distintos:
 *
 * 1. REVALIDA LA SESIÓN en CADA suscripción, sea al destino que sea. El
 *    handshake comprueba el token una sola vez, al abrir la conexión, y a partir
 *    de ahí la sesión vive por su cuenta: sin esto, una conexión abierta
 *    sobrevive al logout, al bloqueo por un administrador y al cambio de
 *    contraseña, y sigue admitiendo suscripciones nuevas durante horas. Choca de
 *    frente con el cuidado del lado REST, donde JwtAuthFilter recarga el usuario
 *    en cada petición precisamente para que un bloqueo surta efecto en el acto.
 *
 * 2. AUTORIZA los destinos de chat en concreto. Cualquier otro —/topic/mapa,
 *    /user/queue/…— pasa una vez revalidada la sesión, porque su autorización es
 *    simplemente estar autenticado.
 *
 * LIMITACIÓN CONOCIDA, Y NO ES PEQUEÑA: revalidar aquí impide suscripciones
 * NUEVAS, pero NO cierra la conexión ni cancela las suscripciones que ya
 * estuvieran hechas. Un usuario bloqueado mientras tenía abierto un chat sigue
 * recibiendo lo que se publique en él hasta que su cliente se desconecte. Cerrar
 * sesiones activas exige un registro de sesiones WebSocket vivas por usuario —
 * escuchar SessionConnectedEvent y SessionDisconnectEvent, guardar el sessionId
 * y cerrarlas desde AdminService al bloquear—, y eso es una pieza nueva que
 * queda fuera de alcance (ver docs/api-rest.md §17). Lo que sí se cierra es la
 * puerta a abrir nada nuevo, que es por donde se entra a un chat ajeno.
 *
 * Dentro del espacio de nombres del chat deniega por defecto. La distinción importa
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
    private final TokenRevocadoService tokenRevocadoService;
    private final UserDetailsServiceImpl userDetailsService;

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

        UsuarioPrincipal usuario = UsuarioPrincipal.desde(accesor.getUser());
        if (usuario == null) {
            throw new MessagingException("No se puede suscribir a un chat sin sesión autenticada");
        }

        // Antes que cualquier decisión sobre el destino: si la sesión ya no vale,
        // da igual a qué se quiera suscribir.
        revalidarSesion(accesor, usuario);

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

    /**
     * Vuelve a comprobar lo que el handshake comprobó al abrir la conexión.
     *
     * Son las mismas cuatro condiciones que JwtAuthFilter aplica a cada petición
     * HTTP, y en el mismo orden, para que una sesión WebSocket no sea una forma de
     * conservar privilegios que la API REST ya habría retirado:
     * el token no ha caducado, no se ha revocado con un logout, la cuenta sigue
     * activa y el token no es anterior al último cambio de contraseña.
     *
     * El usuario se recarga de la base de datos, no se reutiliza el que quedó en
     * los atributos de la sesión: aquel es una foto del momento del handshake, y
     * es justo lo que puede haber quedado obsoleto. El coste es una consulta por
     * suscripción, que es una operación poco frecuente —una por conversación que
     * se abre—, no por mensaje.
     *
     * SIN DATOS DEL TOKEN SE RECHAZA, en vez de dejar pasar por no poder
     * comprobar. Solo se llegaría ahí con una sesión abierta antes de que esto
     * existiera o si alguien retirase JwtHandshakeInterceptor de la cadena; en los
     * dos casos, no poder verificar una sesión es motivo para no fiarse de ella.
     */
    private void revalidarSesion(StompHeaderAccessor accesor, UsuarioPrincipal usuario) {
        Map<String, Object> atributos = accesor.getSessionAttributes();
        Object datos = atributos == null ? null : atributos.get(JwtHandshakeInterceptor.ATRIBUTO_TOKEN);

        if (!(datos instanceof JwtHandshakeInterceptor.DatosToken token)) {
            log.warn("Suscripción rechazada: la sesión del usuario {} no lleva datos del token",
                    usuario.getId());
            throw new MessagingException("Vuelve a conectarte para continuar");
        }

        if (token.expiraEn() != null && !Instant.now().isBefore(token.expiraEn())) {
            log.debug("Suscripción rechazada: el token del usuario {} ha caducado", usuario.getId());
            throw new MessagingException("Tu sesión ha caducado");
        }

        if (tokenRevocadoService.estaRevocado(token.jti())) {
            log.debug("Suscripción rechazada: el usuario {} había cerrado sesión", usuario.getId());
            throw new MessagingException("Tu sesión ya no es válida");
        }

        UsuarioPrincipal actual;
        try {
            actual = (UsuarioPrincipal) userDetailsService.loadUserByUsername(usuario.getUsername());
        } catch (UsernameNotFoundException e) {
            log.warn("Suscripción rechazada: la cuenta del usuario {} ya no existe", usuario.getId());
            throw new MessagingException("Tu sesión ya no es válida");
        }

        if (!actual.isEnabled()) {
            log.warn("Suscripción rechazada: la cuenta del usuario {} está bloqueada", usuario.getId());
            throw new MessagingException("Tu cuenta ha sido bloqueada por un administrador");
        }

        if (!actual.tokenEmitidoSigueVigente(token.emitidoEn())) {
            log.debug("Suscripción rechazada: el token del usuario {} es anterior al cambio de contraseña",
                    usuario.getId());
            throw new MessagingException("Tu sesión ya no es válida: la contraseña ha cambiado");
        }
    }
}
