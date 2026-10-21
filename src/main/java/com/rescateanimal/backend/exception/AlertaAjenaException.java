package com.rescateanimal.backend.exception;

/**
 * Alguien que no es el dueño de una alerta intenta deslizar uno de sus
 * avistamientos. Se traduce a 403 ALERTA_AJENA.
 *
 * Solo el dueño revisa los avistamientos de su alerta: es quien puede
 * reconocer a su animal en la foto, y el match abre un chat con el testigo
 * que nadie más tiene por qué abrir.
 *
 * Código propio, y no el ACCESO_DENEGADO genérico, porque POST /api/matches ya
 * tiene otro 403 —PUBLICACION_PROPIA— y el cliente tiene que distinguirlos. Es
 * el mismo criterio que ANIMAL_AJENO y CHAT_AJENO: el recurso existe, pero la
 * decisión no es tuya.
 */
public class AlertaAjenaException extends RuntimeException {

    public AlertaAjenaException(String mensaje) {
        super(mensaje);
    }
}
