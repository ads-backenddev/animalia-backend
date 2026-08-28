package com.rescateanimal.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cambio del idioma de la aplicación (RF-18).
 *
 * Body de PUT /api/usuarios/me/idioma.
 *
 * El valor admitido lo decide el enum Idioma, no una anotación con la lista
 * escrita a mano: así añadir un idioma es tocar un sitio y no dos que pueden
 * quedar en desacuerdo. Aquí solo se comprueba que venga algo; que sea uno de los
 * admitidos lo valida UsuarioService, que es quien puede dar un mensaje con la
 * lista real.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CambiarIdiomaRequest {

    @NotBlank(message = "el idioma es obligatorio")
    private String idioma;
}
