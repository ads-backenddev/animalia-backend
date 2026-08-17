package com.rescateanimal.backend.model.enums;

/**
 * Estado de una alerta de mascota perdida.
 * Al pasar a ENCONTRADA se deja de mostrar en el mapa (RF-14).
 */
public enum EstadoAlerta {
    ACTIVA,
    ENCONTRADA,
    ELIMINADA
}
