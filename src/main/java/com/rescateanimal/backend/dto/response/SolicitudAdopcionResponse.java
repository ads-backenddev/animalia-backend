package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Un ciudadano que ha mostrado interés por un animal de la protectora (RF-10):
 * lo que el requisito llama "usuarios que han querido ponerse en contacto".
 *
 * Es la cara de un MATCH_REGISTRO de tipo ADOPCION con interesado = true, visto
 * desde el lado de la protectora. Los descartes (interesado = false) viven en la
 * misma tabla y NO son solicitudes: nunca aparecen aquí.
 *
 * QUÉ DATOS DEL CIUDADANO SE EXPONEN, Y POR QUÉ NO MÁS. El interesado viaja como
 * AutorResponse —id, nombre y foto—, sin email ni teléfono. No es una omisión
 * pendiente de completar: el modelo no tiene ningún campo con el que un usuario
 * declare que quiere compartir su contacto, así que no existe forma de saber si
 * lo ha consentido, y RNF-10 no permite suponer que sí. El canal por el que el
 * ciudadano comparte sus datos si quiere es el chat que abre el propio match, y
 * por eso va aquí su identificador.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolicitudAdopcionResponse {

    /** Id del MATCH_REGISTRO que originó la solicitud. */
    private Long id;

    /** Datos públicos del ciudadano interesado: nunca su email ni su teléfono. */
    private AutorResponse interesado;

    private Long animalId;
    private String animalNombre;

    /**
     * Estado del animal en el momento de la consulta, no cuando se deslizó.
     *
     * La protectora necesita distinguir una solicitud sobre un animal que sigue
     * DISPONIBLE —que es la que tiene que atender— de una sobre uno que ya
     * adoptó o retiró, que conserva su chat pero ya no requiere gestión.
     */
    private String animalEstado;

    /**
     * Chat abierto por el match, por el que se responde al ciudadano (RF-12).
     *
     * En la práctica siempre existe, porque deslizar con interés lo crea en la
     * misma transacción que el match. Se declara opcional y se omite del JSON si
     * falta para que un dato incompleto no se confunda con un chat de id nulo.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long chatId;

    /** Cuándo mostró interés el ciudadano. */
    private Instant createdAt;
}
