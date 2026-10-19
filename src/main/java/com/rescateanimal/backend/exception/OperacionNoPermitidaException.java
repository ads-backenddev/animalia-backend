package com.rescateanimal.backend.exception;

import lombok.Getter;

/**
 * La operación es sintácticamente válida pero no tiene sentido en este dominio:
 * deslizar la propia publicación, o pedir un tipo que todavía no existe. Se
 * traduce a 400 con el código que indique quien la lanza, para que el cliente
 * pueda distinguir el motivo sin leer el texto.
 */
@Getter
public class OperacionNoPermitidaException extends RuntimeException {

    private final String codigo;

    public OperacionNoPermitidaException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }
}
