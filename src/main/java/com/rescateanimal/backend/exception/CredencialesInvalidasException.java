package com.rescateanimal.backend.exception;

/**
 * Email inexistente o contraseña incorrecta en el login (RF-13).
 * Se traduce a 401 en GlobalExceptionHandler.
 *
 * El mensaje es deliberadamente idéntico en ambos casos, para no revelar qué
 * emails están registrados.
 */
public class CredencialesInvalidasException extends RuntimeException {

    public CredencialesInvalidasException(String mensaje) {
        super(mensaje);
    }
}
