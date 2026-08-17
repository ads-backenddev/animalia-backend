package com.rescateanimal.backend.exception;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Punto único de traducción de excepciones de negocio a respuestas HTTP.
 *
 * Esqueleto: sin handlers todavía. Se añadirá un @ExceptionHandler por cada
 * excepción de negocio conforme se implementen los endpoints, cubriendo los
 * códigos del contrato: 400, 401, 403, 404, 409 (email o alerta duplicada),
 * 429 (límite de matches) y 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
}
