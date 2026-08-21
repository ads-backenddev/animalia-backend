package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Respuesta del login (RF-13): el JWT con el que el cliente autenticará el resto
 * de peticiones, y los datos del usuario para no obligar a una llamada extra.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    /** Esquema de la cabecera Authorization; siempre "Bearer". */
    private String tipo;

    /** Momento en que el token deja de ser válido. */
    private Instant expiraEn;

    private UsuarioResponse usuario;
}
