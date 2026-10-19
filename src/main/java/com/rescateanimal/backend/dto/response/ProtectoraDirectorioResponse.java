package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Una protectora tal y como aparece en el directorio público (RF-06).
 *
 * DELIBERADAMENTE NO ES {@link ProtectoraResponse}, que es lo que devuelve el
 * registro (RF-01) y lo que §9 anunciaba para este listado antes de construirlo.
 * Aquel arrastra dentro un {@link UsuarioResponse} entero —rol, idioma, activo,
 * área geográfica y la fecha de alta de la cuenta—, y de todo eso el directorio
 * necesita exactamente dos campos. Reutilizarlo habría publicado el resto sin
 * que ninguna decisión lo respaldara, que es justo la forma en que se filtran
 * los datos que nadie quiso enseñar. Lo que se expone aquí está enumerado campo
 * a campo, y cada uno tiene su motivo.
 *
 * TAMPOCO LLEVA {@code estado}, a diferencia de ProtectoraResponse: el listado
 * solo contiene protectoras ACTIVA, así que el campo sería una constante. Que
 * las PENDIENTE y RECHAZADA no aparezcan lo garantiza la consulta, no un filtro
 * del cliente.
 *
 * NI {@code direccion}. Un directorio se recorre para encontrar, y lo que hace
 * falta para eso es el nombre, la zona y la reputación; la dirección es para ir,
 * y eso ya es el detalle. Mantenerla fuera de la respuesta que se devuelve de
 * cien en cien también evita que un recorrido del listado entero se lleve de
 * paso la dirección de todas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProtectoraDirectorioResponse {

    private Long id;

    private String nombreOrganizacion;

    /** Comarca o ciudad en la que opera; es también el filtro {@code zona}. */
    private String zonaGeografica;

    /**
     * Si detrás de la protectora hay un particular y no una entidad (V12).
     *
     * VIAJA A PROPÓSITO, y no es un dato interno que se haya escapado. Le dice
     * al cliente dos cosas que necesita: a quién va a escribir —no es lo mismo
     * dirigirse a una asociación que a alguien que rescata por su cuenta— y por
     * qué esta ficha puede no traer teléfono, que si no parecería un fallo. Es
     * un rasgo autodeclarado del perfil público de quien se ha dado de alta para
     * ser encontrado, no un dato personal de nadie.
     */
    private Boolean esPersonaFisica;

    /**
     * Media de 1 a 5 estrellas, omitida del JSON cuando no hay ninguna (RF-23).
     *
     * Es el mismo número, calculado por el mismo método —ValoracionService— que
     * expone {@code GET /api/adopcion/{id}} y el panel de RF-10. No sale de
     * ninguna columna: PROTECTORA.valoracion_media existió, nadie la mantenía y
     * se eliminó en la migración V9.
     *
     * Null y omitido en vez de cero cuando no hay valoraciones: sin datos la
     * media no existe, y un cero se pintaría como la peor nota posible.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal valoracionMedia;

    /**
     * Cuántas valoraciones sostienen esa media.
     *
     * Acompaña siempre a la media porque sin él no se puede interpretar: un 5,00
     * de una sola valoración y un 4,60 de doscientas se pintan igual de bien y no
     * dicen lo mismo. Es cero cuando no hay ninguna, no nulo: "ninguna" es una
     * cantidad conocida.
     */
    private long totalValoraciones;

    /**
     * Cómo contactar con ella, que es lo que RF-06 pide literalmente.
     *
     * Es la única parte de la API por la que salen el email o el teléfono de una
     * cuenta; el porqué, y por qué el teléfono no sale siempre, está en
     * {@link ContactoProtectoraResponse}.
     */
    private ContactoProtectoraResponse contacto;
}
