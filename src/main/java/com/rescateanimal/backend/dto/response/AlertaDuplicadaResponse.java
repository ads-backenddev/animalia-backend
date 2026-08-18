package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alerta ya existente que provoca el rechazo de una publicación duplicada
 * (RF-25). Viaja dentro del cuerpo del 409 para que la aplicación pueda
 * redirigir al ciudadano a la alerta que ya cubre ese caso, en lugar de dejarle
 * con un error sin salida.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertaDuplicadaResponse {

    private Long id;
    private String especie;
    private Long distanciaMetros;
}
