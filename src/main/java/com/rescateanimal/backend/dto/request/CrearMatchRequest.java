package com.rescateanimal.backend.dto.request;

import com.rescateanimal.backend.model.enums.TipoPublicacion;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Deslizamiento sobre una publicación (RF-08, RF-15).
 * Body de POST /api/matches.
 *
 * Los dos sentidos del swipe entran por el mismo endpoint y se distinguen por
 * el campo interesado, igual que se distinguen en la tabla MATCH_REGISTRO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrearMatchRequest {

    @NotNull(message = "la publicación es obligatoria")
    private Long publicacionId;

    /** Decide contra qué tabla se resuelve publicacionId y quién es el otro extremo del chat. */
    @NotNull(message = "el tipo de publicación es obligatorio")
    private TipoPublicacion tipoPublicacion;

    /**
     * true: match, abre chat y consume cuota diaria (RF-08, RF-24).
     * false: descarte, solo evita que la publicación reaparezca en el feed (RF-15).
     *
     * Sin valor por defecto a propósito: dejarlo implícito convertiría un cuerpo
     * incompleto en un match involuntario.
     */
    @NotNull(message = "hay que indicar si hay interés o es un descarte")
    private Boolean interesado;
}
