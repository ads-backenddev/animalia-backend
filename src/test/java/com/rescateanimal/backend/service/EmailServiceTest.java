package com.rescateanimal.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que compone EmailService, sea cual sea el proveedor, y que un fallo de
 * envío no salga de aquí: la solicitud que lo provocó ya está hecha.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EmailServiceTest {

    private static final String URL = "https://animalia.test/restablecer-password";
    private static final String DESTINATARIO = "destinataria.de.prueba@example.com";

    @Mock
    private EmailSender emailSender;

    @Test
    @DisplayName("El correo de recuperación lleva el enlace con el token codificado, en texto y en HTML con el nombre escapado")
    void componeElCorreoDeRecuperacion() {
        new EmailService(emailSender, URL).enviarEnlaceRecuperacion(DESTINATARIO, "<b>Ana</b>", "a+b/c=");

        ArgumentCaptor<CorreoSaliente> correo = ArgumentCaptor.forClass(CorreoSaliente.class);
        verify(emailSender).enviar(correo.capture());
        CorreoSaliente enviado = correo.getValue();
        String enlace = URL + "?token=a%2Bb%2Fc%3D";
        assertThat(enviado.tipo()).isEqualTo(CorreoSaliente.Tipo.RECUPERACION_PASSWORD);
        assertThat(enviado.para()).isEqualTo(DESTINATARIO);
        assertThat(enviado.asunto()).isEqualTo("Recupera tu contraseña");
        assertThat(enviado.texto()).contains(enlace).contains("Hola <b>Ana</b>:");
        assertThat(enviado.html()).contains("<a href=\"" + enlace + "\">")
                .contains("Hola &lt;b&gt;Ana&lt;/b&gt;:")
                .doesNotContain("<b>Ana</b>");
    }

    @Test
    @DisplayName("Si el proveedor no lo acepta, no sale ninguna excepción")
    void unRechazoNoSePropaga() {
        when(emailSender.enviar(any())).thenReturn(false);

        assertThatCode(() -> new EmailService(emailSender, URL).enviarEnlaceRecuperacion(DESTINATARIO, "Ana", "t"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Tampoco un error que el EmailSender no esperaba, y el log no lleva la dirección")
    void unErrorInesperadoNoSePropaga(CapturedOutput salida) {
        when(emailSender.enviar(any())).thenThrow(new IllegalStateException("fallo con " + DESTINATARIO));

        assertThatCode(() -> new EmailService(emailSender, URL).enviarEnlaceRecuperacion(DESTINATARIO, "Ana", "t"))
                .doesNotThrowAnyException();
        assertThat(salida).contains("Fallo inesperado al enviar el correo RECUPERACION_PASSWORD: IllegalStateException")
                .doesNotContain(DESTINATARIO);
    }
}
