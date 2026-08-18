package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Cuerpo uniforme de error de la API, generado en GlobalExceptionHandler.
 * Los campos nulos se omiten del JSON, de modo que "errores" solo aparece en
 * los fallos de validación.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private LocalDateTime timestamp;
    private int status;

    /** Nombre estándar del código HTTP, por ejemplo "Conflict". */
    private String error;

    /** Código estable de la aplicación, para que el cliente decida sin parsear textos. */
    private String codigo;

    private String mensaje;
    private String ruta;

    /** Solo en errores de validación: campo rechazado -> motivo. */
    private Map<String, String> errores;

    /**
     * Solo en el 409 por alerta duplicada (RF-25): la alerta ya existente a la
     * que hay que redirigir al ciudadano.
     */
    private AlertaDuplicadaResponse alertaExistente;

    /** Solo en el 429 por cuota diaria agotada (RF-24). */
    private Integer limiteDiario;

    /** Solo en el 429: medianoche del día siguiente, cuando vuelve a haber cuota. */
    private LocalDateTime reiniciaEn;
}
