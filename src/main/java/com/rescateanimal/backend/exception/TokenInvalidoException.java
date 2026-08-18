package com.rescateanimal.backend.exception;

/**
 * Token JWT manipulado, caducado, ya usado o de un tipo distinto al esperado.
 * Se traduce a 400 en GlobalExceptionHandler.
 */
public class TokenInvalidoException extends RuntimeException {

    public TokenInvalidoException(String mensaje) {
        super(mensaje);
    }
}
