package com.rescateanimal.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Los correos que envía la plataforma: qué dicen y a quién van. De momento solo
 * el enlace de recuperación de contraseña (RF-17).
 *
 * Por dónde salen no es asunto de esta clase: lo decide el EmailSender del
 * perfil activo —SMTP contra Mailpit fuera de producción, Resend en prod—.
 */
@Slf4j
@Service
public class EmailService {

    private final EmailSender emailSender;
    private final String urlRestablecer;

    public EmailService(EmailSender emailSender,
                        @Value("${app.mail.url-restablecer:http://localhost:8081/restablecer-password}")
                        String urlRestablecer) {
        this.emailSender = emailSender;
        this.urlRestablecer = urlRestablecer;
    }

    /**
     * Envía el enlace con el token de restablecimiento.
     *
     * Se ejecuta fuera del hilo de la petición (@Async) a propósito. Si el envío
     * fuera síncrono, la petición tardaría lo que tarde el proveedor cuando la
     * cuenta existe y volvería de inmediato cuando no: esa diferencia de tiempo
     * es medible y permitiría enumerar los emails registrados por mucho que la
     * respuesta sea siempre 204.
     *
     * Por el mismo motivo un fallo de envío no se propaga: el endpoint debe
     * responder igual funcione o no el proveedor, y la solicitud ya aceptada no
     * se deshace. El EmailSender registra el fallo; aquí solo se protege de lo
     * que no hubiera previsto.
     */
    @Async("tareasEnSegundoPlano")
    public void enviarEnlaceRecuperacion(String destinatario, String nombre, String token) {
        String enlace = urlRestablecer + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        String texto = """
                Hola %s:

                Has solicitado restablecer la contraseña de tu cuenta en la plataforma
                de rescate animal. Abre este enlace para elegir una nueva:

                %s

                El enlace caduca en una hora y solo puede usarse una vez.
                Si no has sido tú, ignora este mensaje: tu contraseña no ha cambiado.
                """.formatted(nombre, enlace);

        // El nombre lo escribió el usuario: en el HTML va escapado, o un nombre
        // con etiquetas acabaría interpretado dentro del correo.
        String html = """
                <p>Hola %s:</p>
                <p>Has solicitado restablecer la contraseña de tu cuenta en la plataforma
                de rescate animal. Abre este enlace para elegir una nueva:</p>
                <p><a href="%s">Elegir una contraseña nueva</a></p>
                <p>El enlace caduca en una hora y solo puede usarse una vez.
                Si no has sido tú, ignora este mensaje: tu contraseña no ha cambiado.</p>
                """.formatted(HtmlUtils.htmlEscape(nombre), HtmlUtils.htmlEscape(enlace));

        CorreoSaliente correo = new CorreoSaliente(CorreoSaliente.Tipo.RECUPERACION_PASSWORD,
                destinatario, "Recupera tu contraseña", html, texto);
        try {
            emailSender.enviar(correo);
        } catch (RuntimeException e) {
            log.error("Fallo inesperado al enviar el correo {}: {}", correo.tipo(), e.getClass().getSimpleName());
        }
    }
}
