package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fijación de la nueva contraseña a partir del token del enlace (RF-17).
 * Body de POST /api/auth/restablecer-password.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestablecerPasswordRequest {

    @NotBlank(message = "el token es obligatorio")
    private String token;

    @NotBlank(message = "la nueva contraseña es obligatoria")
    @Size(min = 8, max = 100, message = "la contraseña debe tener al menos 8 caracteres")
    private String nuevaPassword;
}
