package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.ChatDetalleResponse;
import com.rescateanimal.backend.dto.response.ChatResumenResponse;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Conversaciones del chat en tiempo real (RF-12).
 *
 * El envío en vivo viaja por el destino STOMP /topic/chat/{chatId}; aquí están
 * las consultas y, en MensajeController, la alternativa REST al envío.
 *
 * No hay @PreAuthorize: el permiso no depende del rol sino de participar en la
 * conversación, y eso solo puede decidirse con el chat delante. Lo comprueba
 * ChatService, que responde 403 a cualquier otro usuario.
 */
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * GET /api/chats -> 200, 401
     *
     * Conversaciones del usuario, con el interlocutor, el último mensaje y el
     * número de no leídos. Ordenadas por actividad, la más reciente primero.
     */
    @GetMapping
    public ResponseEntity<List<ChatResumenResponse>> listarMisChats(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(chatService.listarMisChats(usuario.getId()));
    }

    /**
     * GET /api/chats/{id} -> 200, 401, 403, 404
     * Cabecera de la conversación: con quién se habla y a raíz de qué.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ChatDetalleResponse> detalle(
            @PathVariable Long id,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(chatService.detalle(id, usuario.getId()));
    }

    /**
     * GET /api/chats/{id}/mensajes -> 200, 401, 403, 404
     *
     * Histórico paginado, del más reciente al más antiguo. Admite page y size;
     * el orden lo fija el servidor, porque una conversación solo tiene una
     * lectura útil.
     */
    @GetMapping("/{id}/mensajes")
    public ResponseEntity<PaginaResponse<MensajeResponse>> historico(
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable paginacion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(chatService.historico(id, usuario.getId(), paginacion));
    }
}
