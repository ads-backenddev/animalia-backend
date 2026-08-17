package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.UsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfil propio del usuario: consulta, actualización, idioma y borrado de cuenta
 * (RF-02, RF-18, RF-19).
 *
 * Esqueleto: sin endpoints implementados todavía.
 */
@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;
}
