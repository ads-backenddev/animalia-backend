package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rechazo de la solicitud de verificación de una protectora (RF-11).
 *
 * Body de PUT /api/admin/protectoras/{id}/rechazar.
 *
 * Es gemelo de BloquearUsuarioRequest y no el mismo DTO a propósito: son dos
 * decisiones distintas sobre dos entidades distintas, y la del rechazo tiene
 * papeletas de crecer —si algún día se admite volver a solicitar, hará falta
 * decir si se permite—. Compartir la clase habría acoplado esa evolución al
 * bloqueo de usuarios, que no tiene nada que ver.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RechazarProtectoraRequest {

    /**
     * Por qué se rechaza la solicitud. Obligatorio.
     *
     * Con la misma limitación que el motivo del bloqueo: hoy solo llega al log,
     * porque NOTIFICACION no transporta texto. Ver BloquearUsuarioRequest.motivo.
     */
    @NotBlank(message = "el motivo del rechazo es obligatorio")
    @Size(max = 500, message = "el motivo no puede superar los 500 caracteres")
    private String motivo;
}
