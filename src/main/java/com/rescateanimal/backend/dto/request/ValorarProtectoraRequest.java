package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Valoración de 1 a 5 estrellas sobre una protectora (RF-23).
 *
 * Body de PUT /api/protectoras/{protectoraId}/valoracion.
 *
 * EL RANGO SE VALIDA AQUÍ ADEMÁS DE EN LA ENTIDAD Y EN LA TABLA, y las tres
 * capas hacen falta porque fallan en momentos distintos: aquí da un 400 con el
 * campo señalado antes de tocar la base de datos, en la entidad protege a
 * cualquier otro código que cree valoraciones sin pasar por este endpoint, y el
 * CHECK de la tabla es lo único que sigue en pie si alguien escribe por SQL.
 *
 * NO HAY CAMPO DE COMENTARIO. RF-23 pide una puntuación y nada más, y un texto
 * libre visible sobre una organización real trae moderación, derecho de réplica
 * y responsabilidad sobre lo publicado, que es un problema distinto y bastante
 * mayor que una nota de 1 a 5.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValorarProtectoraRequest {

    @NotNull(message = "la puntuación es obligatoria")
    @Min(value = 1, message = "la puntuación mínima es 1")
    @Max(value = 5, message = "la puntuación máxima es 5")
    private Integer puntuacion;
}
