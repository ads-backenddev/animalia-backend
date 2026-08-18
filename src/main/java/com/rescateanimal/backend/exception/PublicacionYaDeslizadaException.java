package com.rescateanimal.backend.exception;

/**
 * El usuario ya había deslizado esa publicación, en cualquiera de los dos
 * sentidos. Se traduce a 409.
 *
 * Es la lectura de negocio de la restricción única
 * (usuario_id, publicacion_id, tipo_publicacion): un solo deslizamiento por
 * usuario y publicación, que es lo que permite al feed excluir lo ya visto.
 */
public class PublicacionYaDeslizadaException extends RuntimeException {

    public PublicacionYaDeslizadaException(String mensaje) {
        super(mensaje);
    }
}
