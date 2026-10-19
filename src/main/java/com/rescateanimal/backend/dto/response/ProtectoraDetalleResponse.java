package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Ficha completa de una protectora del directorio (RF-06).
 *
 * ES PLANA Y NO ENVUELVE A {@link ProtectoraDirectorioResponse}, por lo mismo
 * que {@link AnimalAdopcionDetalleResponse} no envuelve a
 * {@link AnimalAdopcionResponse}: el detalle no es "un elemento del listado más
 * cosas", es otra vista con sus propios campos, y anidar uno dentro del otro
 * obligaría al cliente a desenvolver una capa que no significa nada.
 *
 * TRAE DENTRO LOS ANIMALES Y LAS VALORACIONES porque son exactamente lo que se
 * mira antes de decidir si escribir a una protectora: qué ofrece y qué tal la
 * han tratado quienes ya pasaron por ahí. Pedirlos en llamadas aparte habría
 * dejado tres peticiones para pintar una pantalla. §9 los tenía en un endpoint
 * propio, {@code GET /api/protectoras/{id}/animales}, que ya no hace falta: su
 * paginación vive aquí.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProtectoraDetalleResponse {

    private Long id;

    private String nombreOrganizacion;

    /** Comarca o ciudad en la que opera. */
    private String zonaGeografica;

    /**
     * Dónde está, para quien vaya a acercarse.
     *
     * SE OMITE CUANDO {@code esPersonaFisica} ES CIERTO, por el mismo motivo que
     * el teléfono y con más fuerza todavía: la "dirección" de un particular que
     * rescata por su cuenta es su domicilio. Es la misma lectura del campo que
     * hizo V12 al decidir que la baja de cuenta lo borra (RF-19); sería
     * incoherente considerarlo dato personal para borrarlo y no considerarlo tal
     * para publicarlo.
     *
     * QUEDA UN AGUJERO QUE ESTE BLOQUE NO CIERRA, y conviene decirlo aquí y no
     * solo en la documentación: {@code GET /api/adopcion/{id}} devuelve
     * {@code protectoraDireccion} sin mirar este campo, así que la dirección de
     * una persona física que tenga aunque sea un animal publicado sigue siendo
     * legible por ahí. Es contrato de un endpoint anterior y cambiarlo se sale
     * de RF-06; está anotado en docs/api-rest.md §20 con el arreglo concreto.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String direccion;

    /**
     * Si detrás de la protectora hay un particular y no una entidad (V12).
     *
     * Explica al cliente por qué esta ficha puede venir sin teléfono y sin
     * dirección, que si no parecerían datos que faltan. Ver
     * {@link ProtectoraDirectorioResponse#getEsPersonaFisica()}.
     */
    private Boolean esPersonaFisica;

    /** Desde cuándo está dada de alta. */
    private Instant createdAt;

    /**
     * Cómo contactar con ella (RF-06).
     *
     * El porqué de que aquí sí salgan email y teléfono, cuando en el resto de la
     * API no salen nunca, está en {@link ContactoProtectoraResponse}.
     */
    private ContactoProtectoraResponse contacto;

    /**
     * Media de 1 a 5 estrellas, omitida del JSON cuando no hay ninguna (RF-23).
     *
     * Es el mismo número y el mismo cálculo que expone
     * {@code GET /api/adopcion/{id}}: los dos lo piden a
     * {@code ValoracionService.resumenDe}, así que no pueden discrepar. Hay una
     * prueba que lo comprueba pidiendo los dos endpoints seguidos.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal valoracionMedia;

    /** Cuántas valoraciones sostienen la media. Cero, no nulo, cuando no hay ninguna. */
    private long totalValoraciones;

    /**
     * Sus animales DISPONIBLE, paginados y con los más recientes primero.
     *
     * SOLO LOS DISPONIBLE, a diferencia de {@code /panel/animales}, que enseña
     * también los ADOPTADO y los RETIRADO. Es la misma distinción que ya separa
     * el catálogo público del panel: esto es un escaparate y aquello un
     * histórico de gestión. Un animal ya adoptado no es una oferta, y listarlo
     * aquí llevaría a solicitudes sobre animales que ya tienen casa.
     *
     * ES LO ÚNICO QUE PAGINA DE ESTA RESPUESTA, y por eso los parámetros
     * {@code page} y {@code size} del endpoint son suyos: el resto de la ficha
     * tiene tamaño acotado y los animales son lo único que puede crecer sin
     * límite.
     */
    private PaginaResponse<AnimalAdopcionResponse> animales;

    /**
     * Las valoraciones más recientes recibidas (RF-23).
     *
     * VA ACOTADA, a diferencia de la del panel, que las devuelve todas. La
     * diferencia es a quién sirve cada una: el panel es la protectora leyendo lo
     * suyo, y quiere el histórico entero; esto es una ficha pública que
     * cualquiera abre y que se sirve tantas veces como visitas tenga el
     * directorio, así que devolver mil valoraciones en cada una sería trabajo
     * tirado para pintar las cinco primeras.
     *
     * {@code totalValoraciones} sigue siendo el número real y no la longitud de
     * esta lista: el recuento sale de un COUNT sobre la tabla, no de contar lo
     * que se devuelve. Así el cliente puede decir "5 de 137" sin que le falte
     * ningún dato.
     */
    private List<ValoracionResponse> valoraciones;
}
