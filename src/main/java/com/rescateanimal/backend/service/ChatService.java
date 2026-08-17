package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.MensajeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica del chat en tiempo real: listado de conversaciones, histórico de
 * mensajes y envío por WebSocket (RF-12).
 *
 * Esqueleto: sin métodos implementados todavía.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final MensajeRepository mensajeRepository;
}
