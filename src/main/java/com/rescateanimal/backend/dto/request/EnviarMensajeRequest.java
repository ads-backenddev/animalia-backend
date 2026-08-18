package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Texto de un mensaje del chat (RF-12).
 *
 * Es el mismo cuerpo por las dos vías de envío: el body de
 * POST /api/chats/{id}/mensajes y el payload que viaja al destino STOMP
 * /app/chat/{chatId}/enviar. Deliberadamente no lleva ni el emisor ni el chat:
 * el emisor sale del token y el chat de la ruta, así que nadie puede escribir
 * en nombre de otro ni en un chat distinto manipulando el cuerpo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnviarMensajeRequest {

    /**
     * El límite existe para acotar lo que se difunde por el WebSocket: la
     * columna es TEXT y sin tope un solo mensaje podría saturar el canal de
     * ambos participantes.
     */
    @NotBlank(message = "el mensaje no puede estar vacío")
    @Size(max = 2000, message = "el mensaje no puede superar los 2000 caracteres")
    private String contenido;
}
