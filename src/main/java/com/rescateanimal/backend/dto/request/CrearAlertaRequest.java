package com.rescateanimal.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Publicación de una alerta de mascota perdida (RF-03).
 * Body de POST /api/alertas.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrearAlertaRequest {

    @Size(max = 2000, message = "la descripción no puede superar los 2000 caracteres")
    private String descripcion;

    @Size(max = 500, message = "la URL de la foto no puede superar los 500 caracteres")
    private String fotoUrl;

    @NotBlank(message = "la especie es obligatoria")
    @Size(max = 50, message = "la especie no puede superar los 50 caracteres")
    private String especie;

    @NotNull(message = "la fecha del avistamiento es obligatoria")
    @PastOrPresent(message = "la fecha del avistamiento no puede ser futura")
    private LocalDateTime fechaAvistamiento;

    /** Último lugar donde se vio a la mascota; es la base de la búsqueda por proximidad. */
    @NotNull(message = "la ubicación es obligatoria")
    @Valid
    private UbicacionRequest ubicacion;

    @Positive(message = "el radio de búsqueda debe ser mayor que cero")
    private Integer radioBusqueda;
}
