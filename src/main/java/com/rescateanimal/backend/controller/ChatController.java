package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversaciones y mensajes del chat en tiempo real (RF-12).
 * El envío en vivo viaja por el canal WebSocket /ws/chat/{id}.
 *
 * Esqueleto: sin endpoints implementados todavía.
 */
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
}
