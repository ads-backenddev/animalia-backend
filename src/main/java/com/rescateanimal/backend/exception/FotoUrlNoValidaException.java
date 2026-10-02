package com.rescateanimal.backend.exception;

/**
 * Un fotoUrl que no apunta a una foto subida por quien lo envía a POST
 * /api/fotos: una URL externa, una de otro usuario o una que no existe.
 *
 * Se traduce a 400 VALIDACION con errores.fotoUrl, la misma forma que un error
 * de @Valid, porque para el cliente es lo mismo: ese campo no vale. No se
 * comprueba con una anotación en el DTO porque saber de quién es una foto
 * exige consultar la tabla FOTO.
 */
public class FotoUrlNoValidaException extends RuntimeException {

    public FotoUrlNoValidaException(String mensaje) {
        super(mensaje);
    }
}
