package com.rescateanimal.backend.exception;

/**
 * El usuario intenta deslizar una publicación suya. Se traduce a 403.
 *
 * No es un 400: la petición está bien formada y la publicación existe. Lo que
 * falla es el permiso —nadie puede deslizar su propio contenido, ni para hacer
 * match ni para descartarlo—, y un match consigo mismo abriría además un chat
 * de un usuario con él mismo.
 */
public class PublicacionPropiaException extends RuntimeException {

    public PublicacionPropiaException(String mensaje) {
        super(mensaje);
    }
}
