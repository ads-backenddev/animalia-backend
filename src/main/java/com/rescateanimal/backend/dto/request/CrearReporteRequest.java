package com.rescateanimal.backend.dto.request;

import com.rescateanimal.backend.model.enums.MotivoReporte;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.model.enums.TipoObjetivo;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reporte de una publicación o de un usuario que incumple las normas (RF-26).
 *
 * Body de POST /api/reportes.
 *
 * TRES CAMPOS PARA SEÑALAR UNA COSA, y hacen falta los tres. `tipoObjetivo` dice
 * si se reporta una publicación o a un usuario; `objetivoId` es su id; y
 * `tipoContenido` desambigua a cuál de las tres tablas de publicaciones apunta
 * ese id, porque el 7 existe a la vez como alerta, como avistamiento y como
 * animal. Es el mismo par que ya guarda MATCH_REGISTRO y el mismo que recibe
 * DELETE /api/admin/publicaciones/{id}, de modo que el administrador puede
 * encadenar lo que le llega del reporte sin traducir nada.
 *
 * LA COHERENCIA ENTRE LOS TRES NO SE VALIDA AQUÍ. Que `tipoContenido` esté
 * presente cuando el objetivo es una publicación, y ausente cuando es un
 * usuario, es una regla que relaciona dos campos entre sí, y Bean Validation
 * sobre campos sueltos no sabe expresarla sin una anotación de clase a medida.
 * La comprueba ReporteService, que además es quien puede mirar si el objetivo
 * existe de verdad.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrearReporteRequest {

    @NotNull(message = "el tipo de objetivo es obligatorio")
    private TipoObjetivo tipoObjetivo;

    @NotNull(message = "el id del objetivo es obligatorio")
    @Positive(message = "el id del objetivo debe ser positivo")
    private Long objetivoId;

    /**
     * Obligatorio cuando el objetivo es una publicación, prohibido cuando es un
     * usuario. Lo comprueba el service, no una anotación: ver el javadoc de la
     * clase.
     */
    private TipoContenido tipoContenido;

    @NotNull(message = "el motivo es obligatorio")
    private MotivoReporte motivo;
}
