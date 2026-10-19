package com.rescateanimal.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Envío de correo saliente. De momento solo el enlace de recuperación de
 * contraseña (RF-17).
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String remitente;
    private final String urlRestablecer;

    public EmailService(JavaMailSender mailSender,
                        @Value("${app.mail.remitente:no-reply@rescateanimal.es}") String remitente,
                        @Value("${app.mail.url-restablecer:http://localhost:8081/restablecer-password}")
                        String urlRestablecer) {
        this.mailSender = mailSender;
        this.remitente = remitente;
        this.urlRestablecer = urlRestablecer;
    }

    /**
     * Envía el enlace con el token de restablecimiento.
     *
     * Se ejecuta fuera del hilo de la petición (@Async) a propósito. Si el envío
     * fuera síncrono, la petición tardaría lo que tarde el diálogo SMTP cuando la
     * cuenta existe y volvería de inmediato cuando no: esa diferencia de tiempo
     * es medible y permitiría enumerar los emails registrados por mucho que la
     * respuesta sea siempre 204.
     *
     * Por el mismo motivo un fallo de envío se registra pero no se propaga: el
     * endpoint debe responder igual funcione o no el servidor de correo.
     */
    @Async("tareasEnSegundoPlano")
    public void enviarEnlaceRecuperacion(String destinatario, String nombre, String token) {
        String enlace = urlRestablecer + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        SimpleMailMessage mensaje = new SimpleMailMessage();
        mensaje.setFrom(remitente);
        mensaje.setTo(destinatario);
        mensaje.setSubject("Recupera tu contraseña");
        mensaje.setText("""
                Hola %s:

                Has solicitado restablecer la contraseña de tu cuenta en la plataforma
                de rescate animal. Abre este enlace para elegir una nueva:

                %s

                El enlace caduca en una hora y solo puede usarse una vez.
                Si no has sido tú, ignora este mensaje: tu contraseña no ha cambiado.
                """.formatted(nombre, enlace));

        try {
            mailSender.send(mensaje);
            log.info("Enviado enlace de recuperación de contraseña");
        } catch (MailException e) {
            log.error("No se pudo enviar el email de recuperación de contraseña", e);
        }
    }
}
