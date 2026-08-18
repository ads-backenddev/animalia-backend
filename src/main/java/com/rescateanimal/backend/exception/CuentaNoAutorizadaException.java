package com.rescateanimal.backend.exception;

import lombok.Getter;

/**
 * Credenciales correctas, pero la cuenta no puede operar: bloqueada por el
 * administrador (RF-11) o protectora aún sin verificar (RF-01).
 * Se traduce a 403 en GlobalExceptionHandler.
 *
 * Lleva un código propio porque el cliente necesita distinguir ambos casos para
 * mostrar el mensaje adecuado.
 */
@Getter
public class CuentaNoAutorizadaException extends RuntimeException {

    private final String codigo;

    public CuentaNoAutorizadaException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }
}
