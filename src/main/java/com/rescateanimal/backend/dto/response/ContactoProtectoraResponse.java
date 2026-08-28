package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cómo se contacta con una protectora del directorio (RF-06).
 *
 * =====================================================================
 * AQUÍ SÍ VIAJAN DATOS DE CONTACTO, Y ES LA EXCEPCIÓN DEL PROYECTO.
 * =====================================================================
 *
 * En todo el resto de la API el email y el teléfono de un usuario no salen
 * nunca: los listados de alertas, avistamientos, matches, valoraciones y
 * solicitudes del panel llevan {@link AutorResponse} —id, nombre, foto— y nada
 * más, porque son datos personales y el modelo no tiene ningún campo con el que
 * alguien declare que quiere compartirlos (RNF-10). Este DTO es la única puerta
 * por la que salen, y existe por dos motivos que no se dan en aquellos casos.
 *
 * EL PRIMERO ES QUE RF-06 LO PIDE LITERALMENTE: el directorio de protectoras se
 * consulta "incluyendo su información de contacto". Sin ella el requisito no se
 * cumple, y el endpoint quedaría en una lista de nombres con la que no se puede
 * hacer nada.
 *
 * EL SEGUNDO ES QUE UNA PROTECTORA NO ES UN CIUDADANO. Un particular publica una
 * alerta porque ha perdido a su perro, no para figurar en un directorio: su
 * contacto es un dato que hay que proteger de los demás usuarios, y el canal por
 * el que lo comparte si quiere es el chat (RF-12). Una protectora se da de alta
 * precisamente para ser encontrada y contactada; su email es la dirección con la
 * que atiende adopciones. Negárselo sería protegerla de lo que ha venido a
 * hacer.
 *
 * La diferencia es de criterio, no de descuido, y está escrita también en
 * docs/api-rest.md §9 para que nadie la lea como una inconsistencia con el
 * bloque 7.
 *
 * =====================================================================
 * EL TELÉFONO NO SALE SIEMPRE: DEPENDE DE QUIÉN HAYA DETRÁS.
 * =====================================================================
 *
 * El razonamiento de arriba vale entero para una asociación registrada, cuyo
 * email y cuyo teléfono son los que ella misma publica en su web. No vale igual
 * para el caso que V12 obligó a reconocer: una protectora que es UNA PERSONA
 * PARTICULAR rescatando por su cuenta ({@code esPersonaFisica = true}), cuyo
 * teléfono es su móvil personal.
 *
 * Ahí la línea se traza entre los dos canales, y no es arbitraria:
 *
 * - EL EMAIL SALE SIEMPRE. Es el identificador sobre el que está construida la
 *   cuenta, es asíncrono —se lee cuando se puede, se filtra, se ignora— y es el
 *   único canal sin el cual RF-06 se queda sin cumplir para toda una clase de
 *   protectoras. No es gratis y no se pretende que lo sea: es el precio mínimo
 *   de estar en un directorio, y quien no quiera pagarlo no debería estar en él.
 *
 * - EL TELÉFONO NO SALE CUANDO DETRÁS HAY UNA PERSONA. Un móvil es un canal
 *   directo e inmediato: suena a las tres de la madrugada, no se filtra, y es el
 *   más fuerte de los tres identificadores a la hora de cruzarlo con otras
 *   fuentes hasta dar con un nombre y un domicilio. Además es OPCIONAL en el
 *   modelo, así que quien lo puso pudo hacerlo para que el administrador lo
 *   verificara (RF-11), no para que apareciera en un listado público que
 *   cualquiera puede recorrer entero. El coste de equivocarse no es simétrico:
 *   ocultarlo molesta a quien quería que le llamaran, enseñarlo expone un número
 *   personal de forma irreversible, y entre esas dos se elige la primera.
 *
 * Se omite del JSON en vez de viajar como null, igual que el resto de campos
 * opcionales de la API: así "esta protectora no da teléfono" y "esta protectora
 * es un particular" se ven igual desde fuera, sin que la forma de la respuesta
 * delate cuál de las dos cosas pasa.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactoProtectoraResponse {

    /** Siempre presente: es la cuenta con la que la protectora atiende. */
    private String email;

    /**
     * Solo cuando la protectora es una entidad y lo ha rellenado.
     *
     * Se omite del JSON si falta, sea porque no lo dio o porque detrás hay una
     * persona física. Ver el bloque de arriba.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String telefono;
}
