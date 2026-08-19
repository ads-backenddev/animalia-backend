package com.rescateanimal.backend.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * Convierte en Principal de la sesión STOMP al usuario que
 * JwtHandshakeInterceptor ya validó (docs/api-rest.md §13).
 *
 * Sin esto, la sesión WebSocket es anónima aunque el handshake haya exigido un
 * JWT válido: el usuario queda en los atributos de la sesión, que basta para un
 * canal de difusión pura como /topic/mapa, pero no para nada que dependa de
 * QUIÉN está al otro lado. El chat lo necesita en los dos sentidos (RF-12):
 * para saber quién escribe en /app/chat/{chatId}/enviar y para que
 * ChatSuscripcionInterceptor pueda comprobar si quien se suscribe a
 * /topic/chat/{chatId} participa en ese chat. Es también la pieza que le faltaba
 * al prefijo /user/ de RF-05.
 *
 * El orden importa y es el que garantiza Spring: los HandshakeInterceptor se
 * ejecutan antes que este método, así que cuando se llama el token ya está
 * validado y el atributo puesto. Si no lo estuviera, la conexión ya se habría
 * rechazado con 401 y no se llegaría hasta aquí.
 */
@Slf4j
@Component
public class UsuarioHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {

        Object autenticado = attributes.get(JwtHandshakeInterceptor.ATRIBUTO_USUARIO);

        if (autenticado instanceof UsuarioPrincipal usuario) {
            // Se envuelve en una Authentication porque UserDetails no es un
            // Principal: es el envoltorio el que aporta getName(), que es lo que
            // Spring usa para resolver los destinos /user/.
            return new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities());
        }

        // Defensa en profundidad: solo se llega aquí si alguien retirase
        // JwtHandshakeInterceptor de la cadena. Sin Principal, el chat rechaza
        // tanto la suscripción como el envío, así que la sesión queda inservible
        // en vez de quedar abierta y anónima.
        log.warn("Handshake WebSocket sin usuario en los atributos: la sesión queda sin Principal");
        return null;
    }
}
