package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alta de un usuario con rol CIUDADANO (RF-01).
 * Body de POST /api/auth/registro/ciudadano.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegistroCiudadanoRequest {

    @NotBlank(message = "el nombre es obligatorio")
    @Size(max = 100, message = "el nombre no puede superar los 100 caracteres")
    private String nombre;

    @NotBlank(message = "el email es obligatorio")
    @Email(message = "debe ser una dirección de email válida")
    @Size(max = 150, message = "el email no puede superar los 150 caracteres")
    private String email;

    @NotBlank(message = "la contraseña es obligatoria")
    @Size(min = 8, max = 100, message = "la contraseña debe tener al menos 8 caracteres")
    private String password;

    @Size(max = 20, message = "el teléfono no puede superar los 20 caracteres")
    private String telefono;

    @Size(max = 150, message = "el área geográfica no puede superar los 150 caracteres")
    private String areaGeografica;

    @Size(max = 10, message = "el idioma no puede superar los 10 caracteres")
    private String idioma;
}
