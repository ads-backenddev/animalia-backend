package com.rescateanimal.backend.dto.request;

import com.rescateanimal.backend.dto.validation.UrlHttps;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Personalización del perfil propio (RF-02).
 *
 * Body de PUT /api/usuarios/me.
 *
 * NO LLEVA EMAIL NI CONTRASEÑA, y su ausencia es la decisión de diseño de este
 * DTO. RF-02 habla de personalizar el perfil —foto, nombre, área, teléfono— y no
 * menciona ninguno de los dos. Además cada uno tiene su motivo propio:
 *
 * - El email identifica la cuenta y es con lo que se inicia sesión. Cambiarlo por
 *   un PUT de perfil permitiría apoderarse de una sesión ajena olvidada y
 *   quedarse con la cuenta sin conocer la contraseña. Hacerlo bien exige
 *   verificar la dirección nueva antes de activarla, y ese flujo —correo de
 *   confirmación, token, dirección pendiente— es un bloque en sí mismo.
 * - La contraseña ya tiene su camino en RF-17, que además invalida las sesiones
 *   abiertas al cambiarla. Duplicarlo aquí sería tener dos sitios donde mantener
 *   la misma regla.
 *
 * Los cuatro campos se envían siempre: es un PUT y fija el perfil entero, así que
 * omitir uno lo deja vacío en lugar de conservarlo. Los tres opcionales admiten
 * null explícito, que es como se borra un teléfono o una foto.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActualizarPerfilRequest {

    @NotBlank(message = "el nombre es obligatorio")
    @Size(max = 100, message = "el nombre no puede superar los 100 caracteres")
    private String nombre;

    @Size(max = 20, message = "el teléfono no puede superar los 20 caracteres")
    private String telefono;

    @Size(max = 150, message = "el área geográfica no puede superar los 150 caracteres")
    private String areaGeografica;

    @Size(max = 500, message = "la URL de la foto no puede superar los 500 caracteres")
    @UrlHttps
    private String fotoUrl;
}
