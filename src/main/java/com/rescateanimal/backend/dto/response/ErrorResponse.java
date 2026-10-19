package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
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

    /** Momento del error, en UTC con su "Z": es un instante, no una hora local. */
    private Instant timestamp;
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

    /**
     * Solo en el 429: medianoche del día siguiente, cuando vuelve a haber cuota.
     *
     * Sigue siendo LocalDateTime, y no Instant como el resto de marcas de
     * tiempo de la API, porque no es lo mismo: la cuota de RF-24 se cuenta por
     * día natural (LIMITE_MATCH_DIARIO.fecha es un DATE) y "mañana a las 00:00"
     * es una fecha de calendario, no un punto en la línea del tiempo.
     * Convertirlo a Instant obliga a elegir el calendario de quién —el del
     * servidor o el del usuario, que puede estar en Canarias—, y eso es una
     * decisión del bloque de RF-24, no de una migración de tipos.
     */
    private LocalDateTime reiniciaEn;
}
