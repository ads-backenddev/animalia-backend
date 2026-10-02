package com.rescateanimal.backend.dto.request;

import com.rescateanimal.backend.model.enums.Tamanyo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Modificación de un animal ya publicado (RF-07).
 * Body de PUT /api/adopcion/{id}.
 *
 * Repite los campos de CrearAnimalRequest en vez de heredar de él: son dos
 * contratos distintos que hoy coinciden, y atarlos haría que cualquier campo
 * nuevo en el alta apareciera automáticamente en la edición. Al ser un PUT, los
 * campos son todos obligatorios: reemplazan por completo lo publicado, y un
 * campo ausente vaciaría el dato en vez de conservarlo.
 *
 * No incluye el estado a propósito: pasar a ADOPTADO es una transición con su
 * propio endpoint (RF-20), no una edición más del catálogo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActualizarAnimalRequest {

    @NotBlank(message = "el nombre es obligatorio")
    @Size(max = 100, message = "el nombre no puede superar los 100 caracteres")
    private String nombre;

    @NotBlank(message = "la especie es obligatoria")
    @Size(max = 50, message = "la especie no puede superar los 50 caracteres")
    private String especie;

    @NotNull(message = "el tamaño es obligatorio")
    private Tamanyo tamanyo;

    @NotBlank(message = "la descripción es obligatoria")
    @Size(max = 2000, message = "la descripción no puede superar los 2000 caracteres")
    private String descripcion;

    @NotBlank(message = "la foto es obligatoria")
    @Size(max = 500, message = "la URL de la foto no puede superar los 500 caracteres")
    private String fotoUrl;

    @NotBlank(message = "la zona geográfica es obligatoria")
    @Size(max = 150, message = "la zona geográfica no puede superar los 150 caracteres")
    private String zonaGeografica;
}
