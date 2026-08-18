package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Una conversación en la lista de chats del usuario (RF-12).
 *
 * Trae ya el último mensaje y el número de no leídos porque es exactamente lo
 * que pinta la pantalla de conversaciones: sin ellos el cliente tendría que
 * pedir el histórico de cada chat solo para rellenar la fila.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResumenResponse {

    private Long id;

    /** El otro extremo de la conversación, visto desde quien pregunta. */
    private AutorResponse otroParticipante;

    /** Ausente mientras nadie haya escrito todavía; se omite del JSON en vez de ir a null. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private MensajeResponse ultimoMensaje;

    /** Mensajes del otro participante que este usuario aún no ha leído. */
    private long noLeidos;

    private LocalDateTime createdAt;
}
