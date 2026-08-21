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
 * DEPENDE DE UN BLOQUE QUE AÚN NO EXISTE, Y CONVIENE SABERLO. Hoy este endpoint
 * devuelve siempre una lista vacía y una media nula, y no porque esté sin
 * implementar: la lectura es real y funciona contra la tabla VALORACION, pero
 * NADA puede escribir en esa tabla todavía. No hay ValoracionService ni
 * POST /api/protectoras/{id}/valoracion, que son del bloque 9. En cuanto ese
 * bloque exista, este endpoint empieza a devolver datos sin tocarlo.
 *
 * POR QUÉ LA MEDIA NO SALE DE PROTECTORA.valoracionMedia. Esa columna existe,
 * pero está documentada como derivada y sin mantener: ningún código la
 * actualiza, así que hoy es nula para todas las protectoras y en cuanto el
 * bloque 9 empiece a insertar valoraciones seguiría siéndolo hasta que alguien
 * la recalcule. La media que se devuelve aquí se calcula sobre las valoraciones
 * reales, de modo que es correcta por construcción y no puede quedar desfasada.
 * Mantener esa columna —o eliminarla— es trabajo del bloque 9.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValoracionesPanelResponse {

    /** Más recientes primero. Vacía mientras el bloque 9 no exista. */
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
