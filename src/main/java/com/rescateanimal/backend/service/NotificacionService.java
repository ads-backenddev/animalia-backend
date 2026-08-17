package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.NotificacionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica de notificaciones: envío al área geográfica del usuario, listado y
 * marcado como leídas (RF-05).
 *
 * Esqueleto: sin métodos implementados todavía.
 */
@Service
@RequiredArgsConstructor
public class NotificacionService {

    private final NotificacionRepository notificacionRepository;
}
