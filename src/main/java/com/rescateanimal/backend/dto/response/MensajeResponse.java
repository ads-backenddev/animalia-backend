package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un mensaje del chat (RF-12).
 *
 * Es el mismo DTO en las tres salidas —histórico REST, respuesta del POST y
 * difusión por /topic/chat/{chatId}—, para que el cliente no tenga que pintar
 * de dos formas distintas el mensaje que envía y el que recibe.
 *
 * Lleva el chatId aunque en el histórico sea redundante: por el WebSocket un
 * cliente puede tener varias conversaciones suscritas a la vez y necesita saber
 * a cuál pertenece lo que le llega.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MensajeResponse {

    private Long id;
    private Long chatId;

    /** Sin email ni teléfono: los datos personales no viajan (RNF-10). */
    private AutorResponse emisor;

    private String contenido;
    private Boolean leido;
    private LocalDateTime createdAt;
}
