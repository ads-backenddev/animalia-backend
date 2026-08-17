package com.rescateanimal.backend.exception;

/**
 * Excepción de negocio para un recurso inexistente. Se traduce a un 404 en
 * GlobalExceptionHandler.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String mensaje) {
        super(mensaje);
    }

    public ResourceNotFoundException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
