package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica del perfil propio: consulta, personalización, cambio de idioma y
 * borrado de cuenta con sus datos asociados (RF-02, RF-18, RF-19).
 *
 * Esqueleto: sin métodos implementados todavía.
 */
@Service
@RequiredArgsConstructor
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
}
