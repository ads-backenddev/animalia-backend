package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rescateanimal.backend.model.enums.EstadoReporte;
import com.rescateanimal.backend.model.enums.MotivoReporte;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.model.enums.TipoObjetivo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Un reporte pendiente de revisar por el administrador (RF-11, RF-26).
 *
 * QUÉ SE ENSEÑA DEL OBJETIVO, Y POR QUÉ NO MÁS. Solo el par (tipoObjetivo,
 * objetivoId), más tipoContenido cuando el objetivo es una publicación. No se
 * resuelve el contenido reportado ni su autor, y no es una simplificación
 * pendiente de completar: un reporte es una acusación SIN REVISAR, y devolver los
 * datos de la persona reportada —a quien la denuncia, en la respuesta del POST, o
 * a cualquiera que liste la cola— convertiría el endpoint en una forma cómoda de
 * sacarle el contacto a alguien con quien se ha discutido (RNF-10). Con el par
 * basta para actuar: DELETE /api/admin/publicaciones/{id} recibe exactamente eso.
 *
 * Del autor sí van id, nombre y foto, vía AutorResponse: son los datos públicos
 * que ya viajan en cualquier otro listado, sin email ni teléfono.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReporteResponse {

    private Long id;

    /** Quién reportó. Datos públicos, sin email ni teléfono. */
    private AutorResponse autor;

    /** PUBLICACION o USUARIO: decide contra qué tabla apunta objetivoId. */
    private TipoObjetivo tipoObjetivo;

    private Long objetivoId;

    /**
     * A qué tabla apunta objetivoId cuando el objetivo es una publicación.
     *
     * Ausente del JSON cuando el objetivo es un usuario, en vez de viajar como
     * null: el id de un usuario no necesita discriminador y un campo presente
     * con valor nulo invitaría a pensar que falta.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TipoContenido tipoContenido;

    private MotivoReporte motivo;
    private EstadoReporte estado;

    private Instant createdAt;
}
