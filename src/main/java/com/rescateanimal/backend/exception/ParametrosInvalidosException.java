package com.rescateanimal.backend.exception;

/**
 * Los parámetros de consulta de una petición no forman una combinación válida.
 *
 * Existe porque @Valid solo llega a los cuerpos JSON: reglas como "latitud,
 * longitud y radio van juntas o no van" no se pueden expresar con anotaciones
 * sobre un @RequestParam suelto. Se traduce a 400.
 */
public class ParametrosInvalidosException extends RuntimeException {

    public ParametrosInvalidosException(String mensaje) {
        super(mensaje);
    }
}
