package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.EnviarMensajeRequest;
import com.rescateanimal.backend.dto.response.ErrorResponse;
import com.rescateanimal.backend.exception.ChatAjenoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.MensajeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.validation.FieldError;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Entrada de mensajes por WebSocket (RF-12).
 *
 * Es el equivalente STOMP de MensajeController: recibe en el destino de
 * aplicación /app/chat/{chatId}/enviar y delega en el mismo service, que guarda
 * el mensaje y lo difunde a /topic/chat/{chatId}.
 *
 * NO lleva @SendTo a propósito, aunque sería lo más corto. @SendTo difundiría lo
 * que devuelve este método, y entonces solo se difundiría lo que entra por
 * WebSocket: un mensaje enviado por la vía REST no le llegaría en vivo al otro
 * participante. Con la difusión dentro del service, las dos vías se comportan
 * igual y además el envío sale después de confirmar la transacción, no antes.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    /** Cola personal por la que se le devuelven los rechazos a quien los provocó. */
    public static final String COLA_ERRORES = "/queue/errores";

    private final MensajeService mensajeService;

    /**
     * Destino /app/chat/{chatId}/enviar.
     *
     * El emisor sale del Principal de la sesión, nunca del payload: el cuerpo
     * solo trae el texto, así que no hay forma de escribir en nombre de otro.
     * Que el usuario participe en el chat lo comprueba el service, igual que en
     * la vía REST — suscribirse y escribir son permisos separados, y quien
     * escribe sin estar suscrito también tiene que pasar el filtro.
     */
    @MessageMapping("/chat/{chatId}/enviar")
    public void enviar(@DestinationVariable Long chatId,
                       @Payload @Valid EnviarMensajeRequest peticion,
                       Principal remitente) {

        UsuarioPrincipal usuario = UsuarioPrincipal.desde(remitente);
        if (usuario == null) {
            throw new ChatAjenoException("La sesión no está autenticada");
        }
        mensajeService.enviar(chatId, usuario.getId(), peticion);
    }

    /**
     * Devuelve el rechazo a quien lo provocó, y solo a él.
     *
     * Sin este manejador, cualquier excepción del método anterior haría que
     * Spring emitiera una trama ERROR de STOMP, y una trama ERROR cierra la
     * conexión: un mensaje demasiado largo tumbaría el chat entero en vez de
     * rechazarse solo. Con broadcast = false el aviso va únicamente a la sesión
     * que lo originó, no a las demás del mismo usuario.
     *
     * El cuerpo es el mismo ErrorResponse de la API REST para que el cliente no
     * tenga que interpretar dos formatos de error distintos.
     */
    @MessageExceptionHandler
    @SendToUser(destinations = COLA_ERRORES, broadcast = false)
    public ErrorResponse alFallar(Exception ex, Message<?> original) {
        HttpStatus estado = estadoDe(ex);
        String destino = SimpMessageHeaderAccessor.getDestination(original.getHeaders());

        if (estado.is5xxServerError()) {
            log.error("Fallo no controlado en {}", destino, ex);
        } else {
            log.debug("Envío rechazado en {}: {}", destino, ex.getMessage());
        }

        ErrorResponse cuerpo = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(estado.value())
                .error(estado.getReasonPhrase())
                .codigo(codigoDe(ex, estado))
                // El texto de un 500 no sale al cliente: podría llevar detalles
                // internos, igual que en GlobalExceptionHandler (RNF-10).
                .mensaje(mensajeDe(ex, estado))
                .ruta(destino)
                .build();

        if (ex instanceof MethodArgumentNotValidException validacion) {
            cuerpo.setErrores(erroresDe(validacion));
        }
        return cuerpo;
    }

    /**
     * OJO con el MethodArgumentNotValidException que se compara aquí: es el de
     * org.springframework.messaging.handler.annotation.support, no el homónimo
     * de org.springframework.web.bind que usa GlobalExceptionHandler. Se llaman
     * igual pero no comparten jerarquía, y quien lanza el fallo de @Valid en un
     * @MessageMapping es PayloadMethodArgumentResolver, que usa el de
     * messaging. Con el de web importado por error, un mensaje vacío o de más
     * de 2000 caracteres no casaba con ninguna rama y se iba al 500.
     */
    private HttpStatus estadoDe(Exception ex) {
        if (ex instanceof ChatAjenoException) {
            return HttpStatus.FORBIDDEN;
        }
        if (ex instanceof ResourceNotFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        if (ex instanceof MethodArgumentNotValidException || ex instanceof OperacionNoPermitidaException) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * El detalle de una validación no se saca de getMessage(): el de la
     * excepción de messaging arrastra la firma del método y el payload entero.
     * Se responde el mismo texto fijo que en REST y el detalle va en `errores`.
     */
    private String mensajeDe(Exception ex, HttpStatus estado) {
        if (estado.is5xxServerError()) {
            return "Se ha producido un error inesperado";
        }
        if (ex instanceof MethodArgumentNotValidException) {
            return "Los datos enviados no son válidos";
        }
        return ex.getMessage();
    }

    /** Campo a campo, con la misma forma que el 400 de GlobalExceptionHandler. */
    private Map<String, String> erroresDe(MethodArgumentNotValidException ex) {
        Map<String, String> errores = new HashMap<>();
        if (ex.getBindingResult() != null) {
            for (FieldError error : ex.getBindingResult().getFieldErrors()) {
                errores.put(error.getField(), error.getDefaultMessage());
            }
        }
        return errores;
    }

    private String codigoDe(Exception ex, HttpStatus estado) {
        if (ex instanceof OperacionNoPermitidaException operacion) {
            return operacion.getCodigo();
        }
        return switch (estado) {
            case FORBIDDEN -> "CHAT_AJENO";
            case NOT_FOUND -> "NO_ENCONTRADO";
            case BAD_REQUEST -> "VALIDACION";
            default -> "ERROR_INTERNO";
        };
    }
}
