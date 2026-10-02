package com.rescateanimal.backend.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * El campo, si trae algo, es una URL https:// absoluta con host.
 *
 * Existe porque las fotoUrl acaban dentro de un <img src> en la app y en el
 * panel: un @Size solo admitía "javascript:...", "data:..." o una cadena sin
 * sentido. Exigir https además evita contenido mixto en el panel.
 *
 * Null y en blanco se dan por válidos a propósito: si el campo es obligatorio
 * ya lo dice su @NotBlank, y en el perfil una cadena vacía significa "quitar la
 * foto" (UsuarioService.limpiar).
 */
@Documented
@Constraint(validatedBy = UrlHttpsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface UrlHttps {

    String message() default "la foto debe ser una URL https:// válida";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
