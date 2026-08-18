package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.EnviarMensajeRequest;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.MensajeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Envío de mensajes y marcado de leídos por REST (RF-12).
 *
 * Va aparte de ChatController aunque comparta prefijo de ruta porque son cosas
 * distintas: aquel consulta conversaciones, este escribe en ellas.
 *
 * Como en ChatController, sin @PreAuthorize: el permiso es participar en el
 * chat, no tener un rol, y lo resuelve el service.
 */
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class MensajeController {

    private final MensajeService mensajeService;

    /**
     * POST /api/chats/{id}/mensajes -> 201, 400, 401, 403, 404
     *
     * Alternativa REST al WebSocket, para el cliente que no tenga conexión STOMP
     * activa. El mensaje se difunde igualmente por /topic/chat/{id}, así que el
     * otro participante lo recibe en vivo aunque el emisor no esté conectado al
     * canal.
     *
     * Sin cabecera Location: un mensaje no tiene URL propia, se lee dentro del
     * histórico de su chat.
     */
    @PostMapping("/{id}/mensajes")
    public ResponseEntity<MensajeResponse> enviar(
            @PathVariable Long id,
            @Valid @RequestBody EnviarMensajeRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        MensajeResponse enviado = mensajeService.enviar(id, usuario.getId(), peticion);
        return ResponseEntity.status(201).body(enviado);
    }

    /**
     * PUT /api/chats/{id}/mensajes/leidos -> 204, 401, 403, 404
     *
     * Marca como leídos los mensajes recibidos del otro participante; los
     * propios no se tocan nunca.
     *
     * Es PUT y no PATCH porque no modifica un subconjunto de campos de un
     * recurso: fija por completo el estado "todo lo recibido está leído", y
     * repetirlo dos veces deja el chat exactamente igual.
     */
    @PutMapping("/{id}/mensajes/leidos")
    public ResponseEntity<Void> marcarLeidos(
            @PathVariable Long id,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        mensajeService.marcarLeidos(id, usuario.getId());
        return ResponseEntity.noContent().build();
    }
}
