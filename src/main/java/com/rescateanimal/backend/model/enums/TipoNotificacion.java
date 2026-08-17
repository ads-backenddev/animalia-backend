package com.rescateanimal.backend.model.enums;

/**
 * Tipo de notificación recibida por un usuario (RF-05).
 * El campo referenciaId de Notificacion apunta al elemento que la origina,
 * y su significado depende de este tipo.
 *
 * NOTA: el SRS define este campo como ENUM pero no enumera sus valores.
 * Estos son una propuesta inicial pendiente de confirmación.
 */
public enum TipoNotificacion {
    ALERTA_CERCANA,
    NUEVO_MATCH,
    NUEVO_MENSAJE,
    PROTECTORA_VERIFICADA,
    ADOPCION_CONFIRMADA,
    REPORTE_RESUELTO
}
