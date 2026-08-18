package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Coordenadas de salida, convertidas desde el Point de PostGIS. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UbicacionResponse {

    private Double latitud;
    private Double longitud;
}
