package com.rescateanimal.backend.dto.request;

import com.rescateanimal.backend.dto.validation.UrlHttps;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Publicación de una alerta de mascota perdida (RF-03).
 * Body de POST /api/alertas.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrearAlertaRequest {

    /**
     * Obligatoria según el contrato (docs/api-rest.md §4): sin una descripción,
     * quien vea el marcador en el mapa no puede reconocer al animal.
     */
    @NotBlank(message = "la descripción es obligatoria")
    @Size(max = 2000, message = "la descripción no puede superar los 2000 caracteres")
    private String descripcion;

    @Size(max = 500, message = "la URL de la foto no puede superar los 500 caracteres")
    @UrlHttps
    private String fotoUrl;

    @NotBlank(message = "la especie es obligatoria")
    @Size(max = 50, message = "la especie no puede superar los 50 caracteres")
    private String especie;

    @NotNull(message = "la fecha del avistamiento es obligatoria")
    @PastOrPresent(message = "la fecha del avistamiento no puede ser futura")
    private Instant fechaAvistamiento;

    /** Último lugar donde se vio a la mascota; es la base de la búsqueda por proximidad. */
    @NotNull(message = "la ubicación es obligatoria")
    @Valid
    private UbicacionRequest ubicacion;

    @Positive(message = "el radio de búsqueda debe ser mayor que cero")
    private Integer radioBusqueda;
}
