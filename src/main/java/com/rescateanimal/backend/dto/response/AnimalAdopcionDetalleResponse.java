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
 * organización: quien quiera contactar tiene el chat que abre el match (RF-12),
 * y desde RF-06 también la ficha de la protectora en
 * GET /api/protectoras/{id}, que sí lleva su contacto porque es un directorio y
 * el requisito lo pide. La diferencia de criterio entre los dos endpoints está
 * razonada en docs/api-rest.md §9.
 *
 * OJO CON protectoraDireccion: se devuelve mire quien mire y sin consultar
 * esPersonaFisica, así que si detrás de la protectora hay un particular esto
 * publica su domicilio. El directorio de RF-06 ya no lo hace —lo omite en ese
 * caso—, y que aquí siga saliendo es una incoherencia conocida, anotada con su
 * arreglo en docs/api-rest.md §20.
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
