package com.rescateanimal.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Edición de una alerta propia (PUT /api/alertas/{id}).
 *
 * Los mismos campos y las mismas reglas que CrearAlertaRequest, a propósito:
 * es un PUT, así que el cliente manda la alerta entera, y una edición no puede
 * dejarla en un estado que la publicación habría rechazado. Lo que la edición
 * añade —volver a pasar RF-25 si cambian la especie o la ubicación, comprobar
 * la foto solo si cambia— lo decide AlertaService.actualizar.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActualizarAlertaRequest {

    @NotBlank(message = "la descripción es obligatoria")
    @Size(max = 2000, message = "la descripción no puede superar los 2000 caracteres")
    private String descripcion;

    @Size(max = 500, message = "la URL de la foto no puede superar los 500 caracteres")
    private String fotoUrl;

    @NotBlank(message = "la especie es obligatoria")
    @Size(max = 50, message = "la especie no puede superar los 50 caracteres")
    private String especie;

    @NotNull(message = "la fecha del avistamiento es obligatoria")
    @PastOrPresent(message = "la fecha del avistamiento no puede ser futura")
    private Instant fechaAvistamiento;

    @NotNull(message = "la ubicación es obligatoria")
    @Valid
    private UbicacionRequest ubicacion;

    @Positive(message = "el radio de búsqueda debe ser mayor que cero")
    private Integer radioBusqueda;
}
