package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Alta de una protectora (RF-01). Crea el Usuario con rol PROTECTORA y su
 * Protectora asociada en estado PENDIENTE, a la espera de la verificación del
 * administrador (RF-11).
 *
 * Body de POST /api/auth/registro/protectora.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegistroProtectoraRequest {

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

    @NotBlank(message = "el nombre de la organización es obligatorio")
    @Size(max = 150, message = "el nombre de la organización no puede superar los 150 caracteres")
    private String nombreOrganizacion;

    @NotBlank(message = "la dirección es obligatoria")
    @Size(max = 250, message = "la dirección no puede superar los 250 caracteres")
    private String direccion;

    @NotBlank(message = "la zona geográfica es obligatoria")
    @Size(max = 150, message = "la zona geográfica no puede superar los 150 caracteres")
    private String zonaGeografica;
}
