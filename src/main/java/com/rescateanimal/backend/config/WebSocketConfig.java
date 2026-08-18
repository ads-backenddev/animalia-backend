package com.rescateanimal.backend.config;

import com.rescateanimal.backend.security.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
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

    @Value("${app.cors.origenes:http://localhost:8081,http://localhost:5173}")
    private List<String> origenesPermitidos;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origenesPermitidos.toArray(String[]::new))
                .addInterceptors(jwtHandshakeInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker en memoria: suficiente para una instancia. Si se escalara a
        // varias, los eventos publicados por una no llegarían a los suscritos en
        // otra y haria falta un broker externo (RabbitMQ o similar).
        registry.enableSimpleBroker("/topic", "/queue");

        // Prefijo de los mensajes que van del cliente al servidor. El mapa es
        // unidireccional (solo servidor -> cliente), pero el chat lo necesitara.
        registry.setApplicationDestinationPrefixes("/app");
    }
}
