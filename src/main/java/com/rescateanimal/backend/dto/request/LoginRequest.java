package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Credenciales de acceso (RF-13). Body de POST /api/auth/login.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "el email es obligatorio")
    @Email(message = "debe ser una dirección de email válida")
    private String email;

    @NotBlank(message = "la contraseña es obligatoria")
    private String password;
}
