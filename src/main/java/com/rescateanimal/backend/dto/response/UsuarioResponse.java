package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.Rol;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Vista de un usuario para su propietario. Nunca expone el passwordHash: es la
 * razón por la que los controllers no devuelven la entidad Usuario.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsuarioResponse {

    private Long id;
    private String nombre;
    private String email;
    private String telefono;
    private String areaGeografica;
    private String fotoUrl;
    private Rol rol;
    private String idioma;
    private Boolean activo;
    private LocalDateTime createdAt;
}
