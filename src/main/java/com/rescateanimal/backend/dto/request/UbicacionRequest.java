package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Coordenadas tal y como viajan por la API.
 *
 * En la entidad la ubicación es un Point de PostGIS; la conversión la hace el
 * service. El cliente envía latitud y longitud porque es lo que devuelve el GPS
 * del móvil, no una geometría.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UbicacionRequest {

    @NotNull(message = "la latitud es obligatoria")
    @DecimalMin(value = "-90.0", message = "la latitud debe estar entre -90 y 90")
    @DecimalMax(value = "90.0", message = "la latitud debe estar entre -90 y 90")
    private Double latitud;

    @NotNull(message = "la longitud es obligatoria")
    @DecimalMin(value = "-180.0", message = "la longitud debe estar entre -180 y 180")
    @DecimalMax(value = "180.0", message = "la longitud debe estar entre -180 y 180")
    private Double longitud;
}
