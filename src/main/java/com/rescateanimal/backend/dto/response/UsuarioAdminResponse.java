package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.Rol;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Un usuario visto desde el panel de administración (RF-11).
 *
 * POR QUÉ ESTE SÍ LLEVA EL EMAIL, A DIFERENCIA DE AutorResponse. No es una
 * incoherencia: son dos audiencias distintas. AutorResponse describe a un
 * ciudadano ante OTRO usuario —la protectora que recibe su solicitud, quien lee
 * un avistamiento— y ahí el email es un dato de contacto que nadie ha
 * consentido compartir (RNF-10). Aquí quien mira es el administrador, y el email
 * es lo único que identifica una cuenta sin ambigüedad: los nombres se repiten,
 * y bloquear la cuenta equivocada por confundir dos "Ana García" es exactamente
 * el error que hay que evitar.
 *
 * NO lleva teléfono, y eso sí es deliberado: administrar una cuenta no requiere
 * poder llamar a su dueño. Es el dato de contacto más sensible de los dos y no
 * aporta nada a ninguna de las decisiones de RF-11.
 *
 * Tampoco lleva passwordHash, por si hiciera falta decirlo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsuarioAdminResponse {

    private Long id;
    private String nombre;

    /** Identifica la cuenta de forma inequívoca al administrar. */
    private String email;

    private Rol rol;

    /** false = bloqueada por un administrador; no puede iniciar sesión (RF-11). */
    private Boolean activo;

    private String areaGeografica;

    private Instant createdAt;
}
