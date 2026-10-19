package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.EnviarMensajeRequest;
import com.rescateanimal.backend.dto.response.ErrorResponse;
import com.rescateanimal.backend.exception.ChatAjenoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.service.MensajeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Traducción de un fallo del canal STOMP al ErrorResponse que viaja por
 * /user/queue/errores (docs/api-rest.md §13).
 *
 * Se prueba el manejador directamente y no a través de un cliente STOMP: lo que
 * hay que fijar es el mapeo excepción -> código, y montar un broker en memoria
 * para eso no añadiría nada.
 *
 * El caso de la validación tiene su prueba propia porque es el que se puede
 * romper sin que se note: hay dos MethodArgumentNotValidException distintas en
 * Spring —la de web.bind y la de messaging— y comparar con la que no es
 * compila igual de bien, pero manda un 500 en vez de un 400.
 */
@ExtendWith(MockitoExtension.class)
class ChatWebSocketControllerTest {

    private static final String DESTINO = "/app/chat/42/enviar";

    @Mock
    private MensajeService mensajeService;

    @InjectMocks
    private ChatWebSocketController controller;

    @Test
    @DisplayName("Chat ajeno -> 403 CHAT_AJENO con el destino que falló en la ruta")
    void chatAjenoSeTraduceA403() {
        ErrorResponse error = controller.alFallar(
                new ChatAjenoException("No participas en esta conversación"), mensajeStomp());

        assertThat(error.getStatus()).isEqualTo(403);
        assertThat(error.getCodigo()).isEqualTo("CHAT_AJENO");
        assertThat(error.getMensaje()).isEqualTo("No participas en esta conversación");
        assertThat(error.getRuta()).isEqualTo(DESTINO);
    }

    @Test
    @DisplayName("Chat inexistente -> 404 NO_ENCONTRADO")
    void chatInexistenteSeTraduceA404() {
        ErrorResponse error = controller.alFallar(
                new ResourceNotFoundException("El chat 42 no existe"), mensajeStomp());

        assertThat(error.getStatus()).isEqualTo(404);
        assertThat(error.getCodigo()).isEqualTo("NO_ENCONTRADO");
    }

    @Test
    @DisplayName("Mensaje que no pasa @Valid -> 400 VALIDACION, no 500")
    void validacionSeTraduceA400() {
        ErrorResponse error = controller.alFallar(validacionFallida(), mensajeStomp());

        assertThat(error.getStatus()).isEqualTo(400);
        assertThat(error.getCodigo()).isEqualTo("VALIDACION");
    }

    @Test
    @DisplayName("El 400 detalla el campo, con la misma forma que el de la API REST")
    void validacionDetallaElCampo() {
        ErrorResponse error = controller.alFallar(validacionFallida(), mensajeStomp());

        assertThat(error.getErrores())
                .containsExactly(entry("contenido", "el mensaje no puede superar los 2000 caracteres"));
        // El texto de la excepción arrastra la firma del método y el payload
        // entero, así que no es lo que se le manda al cliente.
        assertThat(error.getMensaje()).isEqualTo("Los datos enviados no son válidos");
    }

    @Test
    @DisplayName("Excepción de negocio con código propio -> 400 conservando su código")
    void operacionNoPermitidaConservaSuCodigo() {
        ErrorResponse error = controller.alFallar(
                new OperacionNoPermitidaException("CHAT_CERRADO", "La conversación está cerrada"),
                mensajeStomp());

        assertThat(error.getStatus()).isEqualTo(400);
        assertThat(error.getCodigo()).isEqualTo("CHAT_CERRADO");
        assertThat(error.getMensaje()).isEqualTo("La conversación está cerrada");
    }

    @Test
    @DisplayName("Fallo inesperado -> 500 sin filtrar el detalle interno (RNF-10)")
    void falloInesperadoNoFiltraElDetalle() {
        ErrorResponse error = controller.alFallar(
                new IllegalStateException("connection pool exhausted at jdbc:postgresql://db:5432"),
                mensajeStomp());

        assertThat(error.getStatus()).isEqualTo(500);
        assertThat(error.getCodigo()).isEqualTo("ERROR_INTERNO");
        assertThat(error.getMensaje()).isEqualTo("Se ha producido un error inesperado");
        assertThat(error.getErrores()).isNull();
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private static Message<?> mensajeStomp() {
        SimpMessageHeaderAccessor cabeceras = SimpMessageHeaderAccessor.create();
        cabeceras.setDestination(DESTINO);
        return MessageBuilder.createMessage(new byte[0], cabeceras.getMessageHeaders());
    }

    /**
     * El mismo fallo que produce PayloadMethodArgumentResolver cuando el
     * @Valid del payload no pasa: la excepción de messaging, no la de web.bind.
     */
    private static MethodArgumentNotValidException validacionFallida() {
        EnviarMensajeRequest peticion = new EnviarMensajeRequest("a".repeat(2001));
        BindingResult binding = new BeanPropertyBindingResult(peticion, "enviarMensajeRequest");
        binding.rejectValue("contenido", "Size", "el mensaje no puede superar los 2000 caracteres");

        return new MethodArgumentNotValidException(mensajeStomp(), parametroDelPayload(), binding);
    }

    private static MethodParameter parametroDelPayload() {
        try {
            return new MethodParameter(ChatWebSocketController.class.getDeclaredMethod(
                    "enviar", Long.class, EnviarMensajeRequest.class, java.security.Principal.class), 1);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("La firma de ChatWebSocketController.enviar ha cambiado", e);
        }
    }
}
