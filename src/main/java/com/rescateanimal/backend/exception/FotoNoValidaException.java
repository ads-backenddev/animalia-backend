package com.rescateanimal.backend.exception;

/**
 * El archivo subido a POST /api/fotos no es una imagen que se pueda aceptar:
 * no es JPEG ni PNG por sus bytes, no se puede decodificar o sus dimensiones
 * son absurdas. Se traduce a 400 FOTO_NO_VALIDA.
 */
public class FotoNoValidaException extends RuntimeException {

    public FotoNoValidaException(String mensaje) {
        super(mensaje);
    }
}
