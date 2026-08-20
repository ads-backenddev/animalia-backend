package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Cabecera de una conversación (RF-12): con quién se habla y a raíz de qué.
 *
 * El contexto de la publicación va aquí y no en cada mensaje: es lo que permite
 * a los dos participantes recordar de qué animal están hablando sin salir del
 * chat. Los mensajes se piden aparte, paginados, en
 * GET /api/chats/{id}/mensajes.
 *
 * DOS ORÍGENES POSIBLES. Un chat nace de un match sobre un avistamiento (RF-08)
 * o sobre un animal en adopción (RF-09), y el contexto que hay que enseñar no es
 * el mismo: en el primero, el avistamiento y la alerta de la que colgaba; en el
 * segundo, el animal y su protectora. `tipoPublicacion` dice cuál de los dos
 * grupos viene relleno, y el otro se omite del JSON en vez de viajar a null, así
 * que el cliente recibe solo los campos que le sirven.
 *
 * Los campos van aplanados y no en dos objetos anidados porque los de
 * avistamiento ya existían antes de que las adopciones abrieran chat: anidarlos
 * ahora habría roto a los clientes del bloque anterior sin ganar nada.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatDetalleResponse {

    private Long id;

    /** El otro extremo de la conversación, visto desde quien pregunta. */
    private AutorResponse otroParticipante;

    /** Match que abrió el chat; el cliente llega desde /api/matches/mios. */
    private Long matchId;

    /** Qué se deslizó: decide cuál de los dos grupos siguientes viene relleno. */
    private TipoPublicacion tipoPublicacion;

    // --- Solo en chats de AVISTAMIENTO ---

    /** Avistamiento deslizado, origen de la conversación. */
    private Long avistamientoId;
    private String avistamientoFotoUrl;
    private String avistamientoDescripcion;

    /** Alerta de la que colgaba el avistamiento, con su estado actual. */
    private Long alertaId;
    private String alertaEspecie;
    private String alertaFotoUrl;
    private String alertaEstado;

    // --- Solo en chats de ADOPCION ---

    /** Animal deslizado, origen de la conversación. */
    private Long animalId;
    private String animalNombre;
    private String animalEspecie;
    private String animalFotoUrl;

    /** DISPONIBLE o ADOPTADO: el chat sigue abierto aunque el animal ya tenga casa. */
    private String animalEstado;

    /** Protectora dueña del animal, que es además el otro participante. */
    private Long protectoraId;
    private String protectoraNombre;

    private Instant createdAt;
}
