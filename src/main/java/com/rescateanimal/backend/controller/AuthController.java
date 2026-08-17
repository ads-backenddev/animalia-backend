package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro, login, logout y recuperación de contraseña
 * (RF-01, RF-13, RF-16, RF-17).
 *
 * Esqueleto: los endpoints se implementarán según docs/api-rest.md. Este
 * controller solo validará la entrada y delegará en AuthService.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
}
