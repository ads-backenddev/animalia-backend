package com.rescateanimal.backend.model.enums;

/**
 * Motivo por el que un usuario reporta una publicación u otro usuario (RF-26).
 *
 * NOTA: el SRS define este campo como ENUM pero no enumera sus valores.
 * Estos son una propuesta inicial pendiente de confirmación.
 */
public enum MotivoReporte {
    CONTENIDO_INAPROPIADO,
    SPAM,
    INFORMACION_FALSA,
    ACOSO,
    ANIMAL_EN_PELIGRO,
    OTRO
}
