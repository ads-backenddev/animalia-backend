package com.rescateanimal.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Envío por SMTP con el JavaMailSender de Spring, fuera de producción: en local
 * y en las pruebas va a Mailpit (docker-compose.yml), que acepta todo y no
 * entrega nada.
 *
 * Perfil !prod y no "local, test": así cualquier arranque que no sea el de
 * producción —también sin perfil— se queda en Mailpit, y no hay forma de que
 * un entorno de desarrollo salga a Resend por despiste.
 *
 * DEL ERROR SOLO SE REGISTRA LA CLASE. El mensaje de una MailSendException
 * enumera las direcciones que fallaron, y el destinatario es un dato personal
 * (RNF-10).
 */
@Slf4j
@Component
@Profile("!prod")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String remitente;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @Value("${app.mail.remitente:no-reply@rescateanimal.es}") String remitente) {
        this.mailSender = mailSender;
        this.remitente = remitente;
    }

    @Override
    public boolean enviar(CorreoSaliente correo) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            // multipart: texto plano y HTML como alternativas del mismo correo.
            MimeMessageHelper partes = new MimeMessageHelper(mensaje, true, StandardCharsets.UTF_8.name());
            partes.setFrom(remitente);
            partes.setTo(correo.para());
            partes.setSubject(correo.asunto());
            partes.setText(correo.texto(), correo.html());
            mailSender.send(mensaje);
            log.info("Correo {} enviado por SMTP", correo.tipo());
            return true;
        } catch (MailException | MessagingException e) {
            log.error("No se pudo enviar el correo {} por SMTP: {}", correo.tipo(), e.getClass().getSimpleName());
            return false;
        }
    }
}
