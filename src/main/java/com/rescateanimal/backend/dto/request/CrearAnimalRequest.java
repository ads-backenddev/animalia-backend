package com.rescateanimal.backend.dto.request;

import com.rescateanimal.backend.model.enums.Tamanyo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Publicación de un animal en el catálogo de adopción (RF-07).
 * Body de POST /api/adopcion.
 *
 * No lleva la protectora: sale del token de quien publica, así que una
 * protectora no puede dar de alta un animal a nombre de otra manipulando el
 * cuerpo. Tampoco lleva el estado, que nace siempre DISPONIBLE y solo cambia
 * por PUT /api/adopcion/{id}/adoptado (RF-20).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrearAnimalRequest {

    @NotBlank(message = "el nombre es obligatorio")
    @Size(max = 100, message = "el nombre no puede superar los 100 caracteres")
    private String nombre;

    /**
     * Texto libre, como en ALERTA.especie: el catálogo no se limita a perros y
     * gatos, y un enum cerrado dejaría fuera cualquier otra especie. El filtro
     * de RF-22 compara sin distinguir mayúsculas por ese mismo motivo.
     */
    @NotBlank(message = "la especie es obligatoria")
    @Size(max = 50, message = "la especie no puede superar los 50 caracteres")
    private String especie;

    /** Criterio de filtrado del catálogo (RF-22), de ahí que sea obligatorio. */
    @NotNull(message = "el tamaño es obligatorio")
    private Tamanyo tamanyo;

    @NotBlank(message = "la descripción es obligatoria")
    @Size(max = 2000, message = "la descripción no puede superar los 2000 caracteres")
    private String descripcion;

    /**
     * Lo que decide una adopción por swipe es la foto (RF-09), así que se exige
     * aunque la columna admita nulos: un animal sin imagen no compite en un
     * catálogo que se navega mirando.
     */
    @NotBlank(message = "la foto es obligatoria")
    @Size(max = 500, message = "la URL de la foto no puede superar los 500 caracteres")
    private String fotoUrl;

    @NotBlank(message = "la zona geográfica es obligatoria")
    @Size(max = 150, message = "la zona geográfica no puede superar los 150 caracteres")
    private String zonaGeografica;
}
