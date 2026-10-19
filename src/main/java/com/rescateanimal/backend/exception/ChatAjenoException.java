package com.rescateanimal.backend.exception;

/**
 * El usuario autenticado no es ninguno de los dos participantes del chat
 * (RF-12). Se traduce a 403: la petición está bien formada y el chat existe, lo
 * que falla es el permiso.
 *
 * Se responde 403 y no 404 porque así lo fija docs/api-rest.md §7. Es una
 * decisión consciente: el 403 confirma que ese chat existe, pero los ids son
 * secuenciales y no revelan nada más —ni participantes, ni contenido, ni
 * siquiera si hay mensajes—, y a cambio el cliente distingue "no es tuyo" de
 * "no existe" sin tener que adivinarlo.
 */
public class ChatAjenoException extends RuntimeException {

    public ChatAjenoException(String mensaje) {
        super(mensaje);
    }
}
