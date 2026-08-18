package com.rescateanimal.backend.exception;

/**
 * Intento de registro con un email que ya tiene cuenta (RF-01).
 * Se traduce a 409 en GlobalExceptionHandler.
 */
public class EmailYaRegistradoException extends RuntimeException {

    public EmailYaRegistradoException(String mensaje) {
        super(mensaje);
    }
}
