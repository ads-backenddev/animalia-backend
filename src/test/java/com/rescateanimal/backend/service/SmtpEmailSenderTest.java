package com.rescateanimal.backend.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SmtpEmailSender con el JavaMailSender simulado: qué mensaje compone y qué deja
 * en el log cuando el servidor SMTP lo rechaza. Que llega de verdad a Mailpit lo
 * dice la prueba de humo.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SmtpEmailSenderTest {

    private static final String REMITENTE = "no-reply@animalia.test";
    private static final String DESTINATARIO = "destinataria.de.prueba@example.com";

    @Mock
    private JavaMailSender mailSender;

    @Test
    @DisplayName("Compone un MIME con remitente, destinatario, asunto y las dos versiones: texto y HTML")
    void componeTextoYHtml(CapturedOutput salida) throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        assertThat(new SmtpEmailSender(mailSender, REMITENTE).enviar(correo())).isTrue();

        ArgumentCaptor<MimeMessage> enviado = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(enviado.capture());
        MimeMessage mensaje = enviado.getValue();
        assertThat(mensaje.getFrom()[0].toString()).isEqualTo(REMITENTE);
        assertThat(mensaje.getAllRecipients()[0].toString()).isEqualTo(DESTINATARIO);
        assertThat(mensaje.getSubject()).isEqualTo("Asunto de prueba");
        ByteArrayOutputStream crudo = new ByteArrayOutputStream();
        mensaje.saveChanges();
        mensaje.writeTo(crudo);
        assertThat(crudo.toString(StandardCharsets.UTF_8))
                .contains("text/plain").contains("Texto de prueba")
                .contains("text/html").contains("<p>HTML de prueba</p>");
        assertThat(salida).contains("Correo RECUPERACION_PASSWORD enviado por SMTP").doesNotContain(DESTINATARIO);
    }

    @Test
    @DisplayName("Un rechazo del servidor devuelve false y deja la clase del error, no el mensaje con la dirección")
    void rechazoSinDireccionEnElLog(CapturedOutput salida) {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("Failed messages: 550 5.1.1 <" + DESTINATARIO + ">: Recipient rejected"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThat(new SmtpEmailSender(mailSender, REMITENTE).enviar(correo())).isFalse();

        assertThat(salida).contains("No se pudo enviar el correo RECUPERACION_PASSWORD por SMTP: MailSendException")
                .doesNotContain(DESTINATARIO);
    }

    private static CorreoSaliente correo() {
        return new CorreoSaliente(CorreoSaliente.Tipo.RECUPERACION_PASSWORD, DESTINATARIO,
                "Asunto de prueba", "<p>HTML de prueba</p>", "Texto de prueba");
    }
}
