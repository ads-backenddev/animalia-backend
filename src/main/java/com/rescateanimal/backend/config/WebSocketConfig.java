package com.rescateanimal.backend.config;

import org.springframework.context.annotation.Configuration;

/**
 * Configuración de WebSockets con STOMP.
 *
 * Pendiente de configurar en la siguiente fase:
 * - Endpoint STOMP y broker de mensajes.
 * - Canales /ws/chat/{id} (RF-12), /ws/notificaciones (RF-05) y /ws/mapa (RF-04).
 * - Autenticación por JWT en el handshake.
 */
@Configuration
public class WebSocketConfig {
}
