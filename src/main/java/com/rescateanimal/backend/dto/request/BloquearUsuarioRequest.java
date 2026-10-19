package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bloqueo de una cuenta por el administrador (RF-11).
 *
 * Body de PUT /api/admin/usuarios/{id}/bloquear.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BloquearUsuarioRequest {

    /**
     * Por qué se bloquea la cuenta. Obligatorio.
     *
     * DÓNDE ACABA, QUE CONVIENE SABERLO: hoy solo en el log del servidor, junto
     * al id del administrador que decidió. NOTIFICACION no tiene ningún campo de
     * texto —solo tipo y referencia_id—, así que el motivo no viaja hasta el
     * usuario: este recibe un CUENTA_BLOQUEADA sin explicación. Hacérselo llegar
     * exige una columna de texto en NOTIFICACION o una tabla de auditoría de
     * acciones del administrador, y ninguna de las dos está decidida.
     *
     * Se exige igualmente porque obliga a quien bloquea a articular el motivo, y
     * porque el día que exista dónde guardarlo el contrato ya lo pide.
     */
    @NotBlank(message = "el motivo del bloqueo es obligatorio")
    @Size(max = 500, message = "el motivo no puede superar los 500 caracteres")
    private String motivo;
}
