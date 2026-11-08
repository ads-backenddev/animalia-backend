package com.rescateanimal.backend.service;

/**
 * Entrega un correo, sin que quien lo compone sepa por dónde sale.
 *
 * Hay una implementación por entorno, elegida por el perfil:
 * - SmtpEmailSender fuera de producción (local y test), contra Mailpit;
 * - ResendEmailSender en el perfil prod, por la API HTTP de Resend.
 *
 * UN FALLO DE ENVÍO NO ES UNA EXCEPCIÓN. El correo es la consecuencia de algo
 * que ya ha pasado —una solicitud de recuperación aceptada— y que no debe
 * deshacerse porque el proveedor no conteste. Cada implementación registra el
 * fallo con lo que sirve para investigarlo —el tipo de correo y el código de
 * estado o la clase de error—, nunca la dirección ni la credencial, y devuelve
 * false.
 */
public interface EmailSender {

    /** @return true si el proveedor aceptó el correo, false si no se pudo entregar. */
    boolean enviar(CorreoSaliente correo);
}
