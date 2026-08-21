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
 * DEPENDE DE UN BLOQUE QUE AÚN NO EXISTE, IGUAL QUE LAS VALORACIONES DEL PANEL
 * DE PROTECTORA. La lectura es real y funciona contra la tabla REPORTE, que
 * existe desde la migración V1, pero NADA puede insertar en ella todavía: no hay
 * ReporteService implementado ni endpoint para reportar contenido, que son del
 * bloque 9 (RF-26). Hasta entonces GET /api/admin/reportes devuelve siempre lista
 * vacía. En cuanto ese bloque exista, empieza a devolver datos sin tocarlo.
 *
 * Tampoco existe todavía la acción de resolver un reporte: el tipo de
 * notificación REPORTE_RESUELTO está en el enum desde el principio, esperándola.
 *
 * QUÉ SE ENSEÑA DEL OBJETIVO. Solo el par (tipoObjetivo, objetivoId), más
 * tipoContenido cuando el objetivo es una publicación. No se resuelve el
 * contenido reportado ni su autor: son tres tablas distintas según el
 * discriminador, y decidir qué se muestra de cada una es parte de diseñar la
 * pantalla de revisión, que pertenece al bloque 9. El administrador ya puede
 * actuar con lo que hay, porque DELETE /api/admin/publicaciones/{id} recibe
 * exactamente ese par.
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
