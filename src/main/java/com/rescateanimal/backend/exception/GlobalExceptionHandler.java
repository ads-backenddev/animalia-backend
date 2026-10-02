package com.rescateanimal.backend.exception;

import com.rescateanimal.backend.dto.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Punto único de traducción de excepciones a respuestas HTTP.
 *
 * Ningún controller captura excepciones: lanzan (o dejan propagar) las del
 * paquete exception/ y aquí se decide el código y el cuerpo, con el formato
 * documentado en docs/api-rest.md.
 *
 * Extiende ResponseEntityExceptionHandler por un motivo concreto: el resolver de
 * @RestControllerAdvice se ejecuta ANTES que el de Spring MVC, así que un
 * @ExceptionHandler(Exception.class) suelto interceptaría también las
 * excepciones propias del framework y convertiría en 500 lo que debe ser 400
 * (JSON malformado), 404 (ruta inexistente) o 405 (método no permitido).
 * Heredando de esta clase, esas excepciones conservan su código correcto y aquí
 * solo se reescribe el cuerpo para que tengan el mismo formato que el resto.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // ---------------------------------------------------------------------
    // Excepciones de Spring MVC: se conserva su código y se unifica el cuerpo
    // ---------------------------------------------------------------------

    /** 400 — validación de un @Valid fallida: se detalla campo a campo. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        Map<String, String> errores = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errores.put(error.getField(), error.getDefaultMessage());
        }
        ErrorResponse cuerpo = base(HttpStatus.BAD_REQUEST, "VALIDACION",
                "Los datos enviados no son válidos", ruta(request));
        cuerpo.setErrores(errores);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(cuerpo);
    }

    /**
     * Reescribe el cuerpo de todas las demás excepciones que ya gestiona
     * ResponseEntityExceptionHandler (body ilegible, método no soportado, ruta
     * inexistente, media type...), respetando el código que Spring determinó.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        HttpStatus estado = HttpStatus.valueOf(statusCode.value());
        return ResponseEntity.status(estado)
                .body(base(estado, codigoDe(estado), mensajeDe(estado), ruta(request)));
    }

    /**
     * 413 — el archivo de POST /api/fotos pasa de spring.servlet.multipart.
     *
     * Spring ya responde 413 por su cuenta; se sobrescribe para darle un código
     * propio y un mensaje que diga el límite, en vez del genérico "ERROR".
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(base(HttpStatus.CONTENT_TOO_LARGE, "FOTO_DEMASIADO_GRANDE",
                        "La foto no puede superar los 5 MB", ruta(request)));
    }

    /** 400 — parámetro de ruta o query con un tipo que no encaja. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> tipoIncorrecto(MethodArgumentTypeMismatchException ex,
                                                        WebRequest request) {
        return respuesta(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO",
                "El parámetro '" + ex.getName() + "' no tiene un valor válido", request);
    }

    // ---------------------------------------------------------------------
    // Excepciones de negocio
    // ---------------------------------------------------------------------

    /** 400 — combinación de parámetros de consulta que no tiene sentido. */
    @ExceptionHandler(ParametrosInvalidosException.class)
    public ResponseEntity<ErrorResponse> parametrosInvalidos(ParametrosInvalidosException ex,
                                                             WebRequest request) {
        return respuesta(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO", ex.getMessage(), request);
    }

    /** 400 — el archivo subido no es un JPEG o PNG que se pueda leer. */
    @ExceptionHandler(FotoNoValidaException.class)
    public ResponseEntity<ErrorResponse> fotoNoValida(FotoNoValidaException ex, WebRequest request) {
        return respuesta(HttpStatus.BAD_REQUEST, "FOTO_NO_VALIDA", ex.getMessage(), request);
    }

    /**
     * 400 — el fotoUrl no es una foto subida por quien la envía. Mismo cuerpo
     * que un @Valid fallido sobre ese campo: para el cliente es lo mismo.
     */
    @ExceptionHandler(FotoUrlNoValidaException.class)
    public ResponseEntity<ErrorResponse> fotoUrlNoValida(FotoUrlNoValidaException ex, WebRequest request) {
        ErrorResponse cuerpo = base(HttpStatus.BAD_REQUEST, "VALIDACION",
                "Los datos enviados no son válidos", ruta(request));
        cuerpo.setErrores(Map.of("fotoUrl", ex.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(cuerpo);
    }

    /** 400 — la operación no encaja en el dominio (deslizar lo propio, tipo no soportado). */
    @ExceptionHandler(OperacionNoPermitidaException.class)
    public ResponseEntity<ErrorResponse> operacionNoPermitida(OperacionNoPermitidaException ex,
                                                              WebRequest request) {
        return respuesta(HttpStatus.BAD_REQUEST, ex.getCodigo(), ex.getMessage(), request);
    }

    /** 400 — token de restablecimiento manipulado, caducado o ya usado (RF-17). */
    @ExceptionHandler(TokenInvalidoException.class)
    public ResponseEntity<ErrorResponse> tokenInvalido(TokenInvalidoException ex, WebRequest request) {
        return respuesta(HttpStatus.BAD_REQUEST, "TOKEN_INVALIDO", ex.getMessage(), request);
    }

    /** 401 — email o contraseña incorrectos (RF-13). */
    @ExceptionHandler(CredencialesInvalidasException.class)
    public ResponseEntity<ErrorResponse> credenciales(CredencialesInvalidasException ex,
                                                      WebRequest request) {
        return respuesta(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", ex.getMessage(), request);
    }

    /** 403 — cuenta bloqueada o protectora sin verificar (RF-01, RF-11). */
    @ExceptionHandler(CuentaNoAutorizadaException.class)
    public ResponseEntity<ErrorResponse> cuentaNoAutorizada(CuentaNoAutorizadaException ex,
                                                            WebRequest request) {
        return respuesta(HttpStatus.FORBIDDEN, ex.getCodigo(), ex.getMessage(), request);
    }

    /** 403 — nadie desliza su propia publicación, ni para match ni para descarte. */
    @ExceptionHandler(PublicacionPropiaException.class)
    public ResponseEntity<ErrorResponse> publicacionPropia(PublicacionPropiaException ex,
                                                           WebRequest request) {
        return respuesta(HttpStatus.FORBIDDEN, "PUBLICACION_PROPIA", ex.getMessage(), request);
    }

    /** 403 — un administrador no puede bloquear su propia cuenta (RF-11). */
    @ExceptionHandler(AutobloqueoException.class)
    public ResponseEntity<ErrorResponse> autobloqueo(AutobloqueoException ex, WebRequest request) {
        return respuesta(HttpStatus.FORBIDDEN, "AUTOBLOQUEO", ex.getMessage(), request);
    }

    /** 403 — el usuario no es ninguno de los dos participantes del chat (RF-12). */
    @ExceptionHandler(ChatAjenoException.class)
    public ResponseEntity<ErrorResponse> chatAjeno(ChatAjenoException ex, WebRequest request) {
        return respuesta(HttpStatus.FORBIDDEN, "CHAT_AJENO", ex.getMessage(), request);
    }

    /** 403 — valorar una protectora con la que nunca se ha tratado (RF-23). */
    @ExceptionHandler(SinInteraccionPreviaException.class)
    public ResponseEntity<ErrorResponse> sinInteraccionPrevia(SinInteraccionPreviaException ex,
                                                              WebRequest request) {
        return respuesta(HttpStatus.FORBIDDEN, "SIN_INTERACCION_PREVIA", ex.getMessage(), request);
    }

    /** 403 — el animal en adopción pertenece a otra protectora (RF-07). */
    @ExceptionHandler(AnimalAjenoException.class)
    public ResponseEntity<ErrorResponse> animalAjeno(AnimalAjenoException ex, WebRequest request) {
        return respuesta(HttpStatus.FORBIDDEN, "ANIMAL_AJENO", ex.getMessage(), request);
    }

    /** 403 — rol insuficiente para el endpoint, lanzado por @PreAuthorize. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> accesoDenegado(AccessDeniedException ex, WebRequest request) {
        return respuesta(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO",
                "No tienes permiso para realizar esta operación", request);
    }

    /** 404 — el recurso solicitado no existe. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> noEncontrado(ResourceNotFoundException ex,
                                                      WebRequest request) {
        return respuesta(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", ex.getMessage(), request);
    }

    /** 409 — el email ya tiene cuenta (RF-01). */
    @ExceptionHandler(EmailYaRegistradoException.class)
    public ResponseEntity<ErrorResponse> emailDuplicado(EmailYaRegistradoException ex,
                                                        WebRequest request) {
        return respuesta(HttpStatus.CONFLICT, "EMAIL_DUPLICADO", ex.getMessage(), request);
    }

    /**
     * 409 — ya hay una alerta equivalente en la zona (RF-25).
     *
     * El cuerpo incluye la alerta existente: el requisito pide redirigir al
     * ciudadano hacia ella, no solo impedirle publicar.
     */
    @ExceptionHandler(AlertaDuplicadaException.class)
    public ResponseEntity<ErrorResponse> alertaDuplicada(AlertaDuplicadaException ex,
                                                         WebRequest request) {
        ErrorResponse cuerpo = base(HttpStatus.CONFLICT, "ALERTA_DUPLICADA",
                ex.getMessage(), ruta(request));
        cuerpo.setAlertaExistente(ex.getAlertaExistente());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(cuerpo);
    }

    /** 409 — el usuario ya había deslizado esa publicación (RF-08, RF-15). */
    @ExceptionHandler(PublicacionYaDeslizadaException.class)
    public ResponseEntity<ErrorResponse> publicacionYaDeslizada(PublicacionYaDeslizadaException ex,
                                                                WebRequest request) {
        return respuesta(HttpStatus.CONFLICT, "SWIPE_DUPLICADO", ex.getMessage(), request);
    }

    /**
     * 429 — cuota diaria de matches agotada (RF-24).
     *
     * El cuerpo lleva el límite y el momento de reinicio: el requisito pide
     * decirle al usuario que podrá continuar al día siguiente, y el cliente no
     * debería tener que deducir esa hora por su cuenta.
     */
    @ExceptionHandler(LimiteMatchesAlcanzadoException.class)
    public ResponseEntity<ErrorResponse> limiteAlcanzado(LimiteMatchesAlcanzadoException ex,
                                                         WebRequest request) {
        ErrorResponse cuerpo = base(HttpStatus.TOO_MANY_REQUESTS, "LIMITE_MATCHES_ALCANZADO",
                ex.getMessage(), ruta(request));
        cuerpo.setLimiteDiario(ex.getLimiteDiario());
        cuerpo.setReiniciaEn(ex.getReiniciaEn());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(cuerpo);
    }

    /** 429 — cupo diario de subidas de fotos agotado. Mismo cuerpo que el de matches. */
    @ExceptionHandler(LimiteFotosAlcanzadoException.class)
    public ResponseEntity<ErrorResponse> limiteFotosAlcanzado(LimiteFotosAlcanzadoException ex,
                                                              WebRequest request) {
        ErrorResponse cuerpo = base(HttpStatus.TOO_MANY_REQUESTS, "LIMITE_FOTOS_ALCANZADO",
                ex.getMessage(), ruta(request));
        cuerpo.setLimiteDiario(ex.getLimiteDiario());
        cuerpo.setReiniciaEn(ex.getReiniciaEn());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(cuerpo);
    }

    /**
     * 409 — choque contra una restricción única de la base de datos.
     *
     * Cubre la ventana entre comprobar que un email está libre e insertarlo: con
     * dos registros simultáneos del mismo email, el segundo llega aquí y debe
     * responder 409 como el resto de duplicados, no 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> integridad(DataIntegrityViolationException ex,
                                                    WebRequest request) {
        log.warn("Violación de integridad en {}: {}", ruta(request), ex.getMostSpecificCause().getMessage());
        return respuesta(HttpStatus.CONFLICT, "CONFLICTO",
                "La operación choca con un registro ya existente", request);
    }

    /**
     * 500 — cualquier fallo no previsto. Se registra la traza completa en el log
     * pero al cliente solo le llega un mensaje genérico, para no filtrar detalles
     * internos ni datos personales (RNF-10).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> noControlada(Exception ex, WebRequest request) {
        log.error("Error no controlado en {}", ruta(request), ex);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO",
                "Se ha producido un error inesperado", request);
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> respuesta(HttpStatus estado, String codigo,
                                                    String mensaje, WebRequest request) {
        return ResponseEntity.status(estado).body(base(estado, codigo, mensaje, ruta(request)));
    }

    private ErrorResponse base(HttpStatus estado, String codigo, String mensaje, String ruta) {
        return ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(estado.value())
                .error(estado.getReasonPhrase())
                .codigo(codigo)
                .mensaje(mensaje)
                .ruta(ruta)
                .build();
    }

    private String ruta(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : request.getDescription(false);
    }

    private String codigoDe(HttpStatus estado) {
        return switch (estado) {
            case BAD_REQUEST -> "PETICION_INVALIDA";
            case NOT_FOUND -> "NO_ENCONTRADO";
            case METHOD_NOT_ALLOWED -> "METODO_NO_PERMITIDO";
            case UNSUPPORTED_MEDIA_TYPE -> "TIPO_NO_SOPORTADO";
            default -> "ERROR";
        };
    }

    private String mensajeDe(HttpStatus estado) {
        return switch (estado) {
            case BAD_REQUEST -> "La petición no se ha podido interpretar";
            case NOT_FOUND -> "El recurso solicitado no existe";
            case METHOD_NOT_ALLOWED -> "Método no permitido para esta ruta";
            case UNSUPPORTED_MEDIA_TYPE -> "Formato de contenido no soportado";
            default -> estado.getReasonPhrase();
        };
    }
}
