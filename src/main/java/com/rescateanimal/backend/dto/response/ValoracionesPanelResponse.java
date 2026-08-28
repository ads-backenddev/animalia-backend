package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Valoraciones recibidas por la protectora, con la media ya calculada (RF-10,
 * RF-23).
 *
 * LA MEDIA SE CALCULA, NO SE LEE DE NINGUNA COLUMNA. PROTECTORA tuvo una
 * valoracion_media que nadie mantenía —ningún código la actualizaba, así que era
 * nula para todas las protectoras— y se eliminó en la migración V9. La media que
 * se devuelve aquí sale de las valoraciones reales, de modo que es correcta por
 * construcción y no puede quedar desfasada. Es el mismo número, y calculado de la
 * misma forma, que expone el detalle de un animal en adopción.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValoracionesPanelResponse {

    /** Más recientes primero. */
    private List<ValoracionResponse> valoraciones;

    /**
     * Media aritmética de 1 a 5, redondeada a dos decimales.
     *
     * Nula, y omitida del JSON, cuando no hay ninguna valoración: sin datos la
     * media no es cero, es que no la hay. Un cero se pintaría como "pésima" y
     * sería falso.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal media;

    /** Cuántas valoraciones sostienen la media, para que el cliente sepa si es significativa. */
    private long total;
}
