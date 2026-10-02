package com.rescateanimal.backend.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Comprueba @UrlHttps parseando con java.net.URI en vez de con una regex: URI ya
 * rechaza espacios y caracteres ilegales, y separa esquema, host y usuario sin
 * que haya que reinventarlo.
 *
 * Se valida el valor recortado porque los services lo guardan recortado
 * (AnimalAdopcionService, UsuarioService).
 */
public class UrlHttpsValidator implements ConstraintValidator<UrlHttps, String> {

    @Override
    public boolean isValid(String valor, ConstraintValidatorContext context) {
        if (valor == null || valor.isBlank()) {
            return true;
        }

        URI uri;
        try {
            uri = new URI(valor.strip());
        } catch (URISyntaxException e) {
            return false;
        }

        // "https://usuario@sitio-falso.com" se lee como si fuera "usuario"; no
        // hay ninguna foto legítima que necesite credenciales en la URL.
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && !uri.getHost().isBlank()
                && uri.getUserInfo() == null;
    }
}
