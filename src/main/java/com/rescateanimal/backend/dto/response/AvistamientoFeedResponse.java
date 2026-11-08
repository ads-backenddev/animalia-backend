package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Tarjeta del feed de swipe (RF-15).
 *
 * Lleva los datos de la alerta a la que responde el avistamiento —especie y
 * foto original— porque el usuario decide comparando las dos imágenes: la del
 * animal perdido y la del animal visto (RF-21).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvistamientoFeedResponse {

    private Long id;
    private String descripcion;
    private String fotoUrl;
    private UbicacionResponse ubicacion;
    private Instant createdAt;
    private AutorResponse autor;

    private Long alertaId;
    private String alertaEspecie;
    private String alertaFotoUrl;

    /**
     * Metros entre el avistamiento y la última ubicación de su alerta, para que
     * la tarjeta diga "A 300 m". Los mide PostGIS sobre geography, igual que la
     * detección de duplicados, y se redondean al metro.
     *
     * null si alguno de los dos puntos falta: un avistamiento sin ubicación no
     * está a ninguna distancia, y cero sería afirmar que está justo encima.
     *
     * Es información, no criterio: el feed se ordena por fecha.
     */
    private Long distanciaMetros;
}
