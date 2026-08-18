package com.rescateanimal.backend.exception;

import com.rescateanimal.backend.dto.response.AlertaDuplicadaResponse;
import lombok.Getter;

/**
 * Ya existe una alerta equivalente: misma especie, dentro del radio de 500 m y
 * en las últimas 24 h (RF-25). Se traduce a 409.
 *
 * Lleva consigo la alerta existente porque el requisito no se conforma con
 * bloquear la publicación: exige redirigir al ciudadano a la alerta que ya
 * cubre ese caso.
 */
@Getter
public class AlertaDuplicadaException extends RuntimeException {

    private final AlertaDuplicadaResponse alertaExistente;

    public AlertaDuplicadaException(String mensaje, AlertaDuplicadaResponse alertaExistente) {
        super(mensaje);
        this.alertaExistente = alertaExistente;
    }
}
