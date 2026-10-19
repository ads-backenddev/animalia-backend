package com.rescateanimal.backend.security;

import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carga el usuario que Spring Security necesita a partir de su email, que es la
 * credencial de acceso de la plataforma (RF-13).
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UsuarioRepository usuarioRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return usuarioRepository.findByEmail(email)
                .map(UsuarioPrincipal::de)
                // El mensaje no distingue entre "no existe" y otros casos, para no
                // revelar qué emails están registrados.
                .orElseThrow(() -> new UsernameNotFoundException("Credenciales no válidas"));
    }
}
