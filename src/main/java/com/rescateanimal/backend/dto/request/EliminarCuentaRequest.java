package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Confirmación para dar de baja la propia cuenta (RF-19).
 *
 * Body de DELETE /api/usuarios/me.
 *
 * PIDE LA CONTRASEÑA AUNQUE YA HAYA TOKEN, y no es una molestia gratuita: el
 * borrado es irreversible y es la única operación de la API que lo es. Un token
 * demuestra que alguien inició sesión alguna vez en las últimas 24 horas, no que
 * quien está tecleando ahora mismo sea el dueño; un móvil desbloqueado sobre una
 * mesa basta para lo primero. La contraseña es lo único que vuelve a preguntar
 * "¿eres tú?" en el momento de la petición.
 *
 * Es el mismo criterio que aplica cualquier servicio serio a cerrar una cuenta, y
 * el motivo por el que DELETE lleva cuerpo aquí pese a que no sea lo habitual en
 * ese verbo: la alternativa —la contraseña en la query— la dejaría escrita en los
 * registros del servidor (RNF-10).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EliminarCuentaRequest {

    @NotBlank(message = "hay que confirmar la contraseña para eliminar la cuenta")
    private String password;
}
