package com.rescateanimal.backend.dto.request;

import com.rescateanimal.backend.dto.validation.UrlHttps;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registro de un avistamiento sobre una alerta existente (RF-21).
 * Body de POST /api/avistamientos.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrearAvistamientoRequest {

    /** Alerta sobre la que se avista al animal; debe existir y estar ACTIVA. */
    @NotNull(message = "la alerta es obligatoria")
    private Long alertaId;

    @NotBlank(message = "la descripción es obligatoria")
    @Size(max = 2000, message = "la descripción no puede superar los 2000 caracteres")
    private String descripcion;

    /**
     * Foto del animal avistado. Es lo que hace útil el swipe: quien perdió a su
     * mascota decide por la imagen (RF-21), así que se pide aunque el modelo la
     * admita nula.
     */
    @NotBlank(message = "la foto es obligatoria para poder identificar al animal")
    @Size(max = 500, message = "la URL de la foto no puede superar los 500 caracteres")
    @UrlHttps
    private String fotoUrl;

    @NotNull(message = "la ubicación es obligatoria")
    @Valid
    private UbicacionRequest ubicacion;
}
