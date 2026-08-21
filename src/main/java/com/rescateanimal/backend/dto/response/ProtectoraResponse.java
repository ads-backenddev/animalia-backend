package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.EstadoProtectora;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vista de una protectora. Tras el registro se devuelve con estado PENDIENTE,
 * a la espera de la verificación del administrador (RF-01, RF-11).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProtectoraResponse {

    private Long id;
    private String nombreOrganizacion;
    private String direccion;
    private String zonaGeografica;
    private EstadoProtectora estado;
    private BigDecimal valoracionMedia;
    private Instant createdAt;

    /** Datos de la cuenta de acceso asociada. */
    private UsuarioResponse usuario;
}
