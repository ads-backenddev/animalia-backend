package com.rescateanimal.backend.exception;

/**
 * Alguien intenta valorar una protectora con la que nunca ha tratado (RF-23).
 * Se traduce a 403 con código SIN_INTERACCION_PREVIA.
 *
 * No es un 400: la petición está perfectamente formada y la puntuación es
 * válida. Lo que falta es el derecho a emitirla, y ese depende del historial
 * entre estas dos partes, no de lo que venga en el cuerpo. Es la misma familia
 * que PublicacionPropiaException y AutobloqueoException: el rol es correcto,
 * pero no sobre ESE objetivo.
 *
 * Tampoco es un 404. La protectora existe y el cliente puede verla; ocultarla
 * detrás de un "no encontrado" mentiría sobre algo que ya es público y, además,
 * dejaría al cliente sin poder distinguir "no existe" de "aún no puedes
 * valorarla", que son dos situaciones con salidas distintas: la segunda se
 * resuelve contactando con la protectora.
 */
public class SinInteraccionPreviaException extends RuntimeException {

    public SinInteraccionPreviaException(String mensaje) {
        super(mensaje);
    }
}
