package com.rescateanimal.backend.config;

import com.rescateanimal.backend.security.ChatSuscripcionInterceptor;
import com.rescateanimal.backend.security.JwtHandshakeInterceptor;
import com.rescateanimal.backend.security.UsuarioHandshakeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * WebSockets con STOMP para el mapa en tiempo real, el chat y las notificaciones
 * (RF-04, RF-05, RF-12).
 *
 * El handshake lo protege JwtHandshakeInterceptor: solo se acepta la conexión si
 * llega un JWT válido, no revocado y de una cuenta activa.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final UsuarioHandshakeHandler usuarioHandshakeHandler;
    private final ChatSuscripcionInterceptor chatSuscripcionInterceptor;

    @Value("${app.cors.origenes:http://localhost:8081,http://localhost:5173}")
    private List<String> origenesPermitidos;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origenesPermitidos.toArray(String[]::new))
                // El interceptor valida el token; el handler convierte al usuario
                // ya validado en el Principal de la sesión, sin el cual el chat no
                // sabría quién escribe ni a qué conversaciones puede suscribirse.
                .setHandshakeHandler(usuarioHandshakeHandler)
                .addInterceptors(jwtHandshakeInterceptor);
    }

    /**
     * Autorización de los mensajes que entran desde el cliente.
     *
     * Un SUBSCRIBE no es una petición HTTP: no pasa por SecurityConfig ni por
     * @PreAuthorize, así que la única forma de impedir que alguien escuche una
     * conversación ajena adivinando su id es interceptar aquí (RF-12).
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registro) {
        registro.interceptors(chatSuscripcionInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker en memoria: suficiente para una instancia. Si se escalara a
        // varias, los eventos publicados por una no llegarían a los suscritos en
        // otra y haria falta un broker externo (RabbitMQ o similar).
        registry.enableSimpleBroker("/topic", "/queue");

        // Prefijo de los mensajes que van del cliente al servidor. El mapa es
        // unidireccional (solo servidor -> cliente); el chat lo usa para
        // /app/chat/{chatId}/enviar (RF-12).
        registry.setApplicationDestinationPrefixes("/app");
    }
}
