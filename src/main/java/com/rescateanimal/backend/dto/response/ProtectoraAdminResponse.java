package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.EstadoProtectora;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Una protectora vista desde el panel de administración (RF-11).
 *
 * Es lo que el administrador necesita para decidir si la verifica: quién dice
 * ser la organización, dónde está y desde cuándo espera. Lleva además el email
 * de su cuenta, por el mismo motivo que UsuarioAdminResponse: es lo que
 * identifica sin ambigüedad a quién se está aprobando o rechazando.
 *
 * No incluye la valoración media ni sus animales: nada de eso interviene en la
 * decisión de verificar, y una protectora PENDIENTE no puede tener todavía ni lo
 * uno ni lo otro.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProtectoraAdminResponse {

    private Long id;

    private String nombreOrganizacion;
    private String direccion;
    private String zonaGeografica;

    private EstadoProtectora estado;

    /** Cuenta de acceso de la protectora: a quién afecta la decisión. */
    private Long usuarioId;
    private String usuarioNombre;
    private String usuarioEmail;

    /** Desde cuándo espera la solicitud. */
    private Instant createdAt;
}
