package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Cabecera de una conversación (RF-12): con quién se habla y a raíz de qué.
 *
 * El contexto de la publicación va aquí y no en cada mensaje: es lo que permite
 * a los dos participantes recordar de qué animal están hablando sin salir del
 * chat. Los mensajes se piden aparte, paginados, en
 * GET /api/chats/{id}/mensajes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDetalleResponse {

    private Long id;

    /** El otro extremo de la conversación, visto desde quien pregunta. */
    private AutorResponse otroParticipante;

    /** Match que abrió el chat; el cliente llega desde /api/matches/mios. */
    private Long matchId;

    /** Avistamiento deslizado, origen de la conversación. */
    private Long avistamientoId;
    private String avistamientoFotoUrl;
    private String avistamientoDescripcion;

    /** Alerta de la que colgaba el avistamiento, con su estado actual. */
    private Long alertaId;
    private String alertaEspecie;
    private String alertaFotoUrl;
    private String alertaEstado;

    private LocalDateTime createdAt;
}
