package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.NotificacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listado y marcado de notificaciones (RF-05).
 * La entrega en vivo viaja por los canales WebSocket /ws/notificaciones y /ws/mapa.
 *
 * Esqueleto: sin endpoints implementados todavía.
 */
@RestController
@RequestMapping("/api/notificaciones")
@RequiredArgsConstructor
public class NotificacionController {

    private final NotificacionService notificacionService;
}
