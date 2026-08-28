package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rescateanimal.backend.model.enums.Tamanyo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Detalle de un animal en adopción, con los datos de la protectora que lo
 * publica (RF-07, RF-10).
 *
 * Los datos de la protectora van embebidos y aplanados, como en
 * ChatDetalleResponse: son los que se leen antes de decidir si contactar, y
 * pedirlos aparte obligaría a una segunda llamada para pintar una sola pantalla.
 *
 * Deliberadamente NO se reutiliza ProtectoraResponse, que arrastra dentro un
 * UsuarioResponse con email y teléfono. Aquí solo viaja lo público de la
 * organización (RNF-10); para hablar con ella está
 * POST /api/protectoras/{id}/contactar, que no expone sus datos de contacto.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalAdopcionDetalleResponse {

    private Long id;
    private String nombre;
    private String especie;
    private Tamanyo tamanyo;
    private String descripcion;
    private String fotoUrl;
    private String zonaGeografica;
    private String estado;
    private Instant createdAt;

    private Long protectoraId;
    private String protectoraNombre;
    private String protectoraDireccion;
    private String protectoraZonaGeografica;

    /**
     * Media de 1 a 5 estrellas (RF-23).
     *
     * Se calcula al leer, con AVG sobre las valoraciones reales. Antes salía de
     * PROTECTORA.valoracion_media, una columna derivada que nadie mantenía y que
     * por tanto era nula siempre; se eliminó en la migración V9.
     *
     * SE OMITE DEL JSON cuando la protectora no tiene ninguna valoración, igual
     * que hace ValoracionesPanelResponse.media con el mismo número. Es el mismo
     * criterio y ahora hacía falta que coincidieran: son los dos sitios donde se
     * expone la reputación de una protectora, y que uno mande `null` y el otro
     * omitiera el campo obligaba al cliente a tratar dos formas de decir lo
     * mismo. Sin datos no hay media; un cero se pintaría como "pésima" y sería
     * falso.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal protectoraValoracionMedia;

    /**
     * Cuántas valoraciones sostienen esa media.
     *
     * Acompaña siempre a la media porque sin él no se puede interpretar: un 5,00
     * de una sola valoración y un 4,60 de doscientas se pintan igual de bien y no
     * dicen lo mismo. Es cero cuando no hay ninguna, no nulo: "ninguna" es una
     * cantidad conocida, a diferencia de la media, que ahí no existe.
     */
    private long protectoraValoracionTotal;
}
