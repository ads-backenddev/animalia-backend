package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.TipoNotificacion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Una notificación del usuario autenticado (GET /api/notificaciones, RF-05).
 *
 * Sin texto: NOTIFICACION solo guarda el tipo y la referencia, así que el
 * cliente compone el mensaje a partir de tipo y abre lo que referenciaId
 * señala —qué tabla es la dice el tipo (docs/api-rest.md §12)—. Tampoco lleva
 * el destinatario: es siempre quien pregunta.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificacionResponse {

    private Long id;
    private TipoNotificacion tipo;
    /** null en los avisos que no apuntan a nada, como CUENTA_BLOQUEADA. */
    private Long referenciaId;
    private Boolean leida;
    private Instant createdAt;
}
