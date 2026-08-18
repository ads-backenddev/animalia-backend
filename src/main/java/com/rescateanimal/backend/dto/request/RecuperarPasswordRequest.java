package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Solicitud del enlace de recuperación de contraseña (RF-17).
 * Body de POST /api/auth/recuperar-password.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecuperarPasswordRequest {

    @NotBlank(message = "el email es obligatorio")
    @Email(message = "debe ser una dirección de email válida")
    private String email;
}
