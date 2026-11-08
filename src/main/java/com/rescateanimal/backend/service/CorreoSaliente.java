package com.rescateanimal.backend.service;

/**
 * Un correo listo para enviar, igual para cualquier proveedor: EmailService lo
 * compone y un EmailSender lo entrega.
 *
 * Lleva las dos versiones del cuerpo. El HTML es lo que ve casi todo el mundo;
 * el texto plano es lo que leen los clientes que no muestran HTML y lo que miran
 * los filtros de spam, que desconfían de un correo solo en HTML.
 *
 * @param tipo   qué correo es; es lo único del correo que llega al log
 * @param para   la dirección del destinatario, que nunca se escribe en el log
 */
public record CorreoSaliente(Tipo tipo, String para, String asunto, String html, String texto) {

    /** Los correos que envía la plataforma. */
    public enum Tipo {
        /** El enlace para elegir una contraseña nueva (RF-17). */
        RECUPERACION_PASSWORD
    }
}
