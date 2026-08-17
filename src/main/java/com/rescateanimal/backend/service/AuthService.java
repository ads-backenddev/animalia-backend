package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica de registro, login, logout y recuperación de contraseña
 * (RF-01, RF-13, RF-16, RF-17).
 *
 * Esqueleto: los métodos se implementarán endpoint por endpoint siguiendo
 * docs/api-rest.md y la skill nuevo-endpoint.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final ProtectoraRepository protectoraRepository;
}
