package com.rescateanimal.backend.security;

import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Puente entre la entidad Usuario y el modelo de usuario de Spring Security.
 *
 * Esqueleto: sin implementar. En la siguiente fase buscará el usuario por email
 * en UsuarioRepository y construirá un UserDetails con su rol como autoridad.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UsuarioRepository usuarioRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        throw new UnsupportedOperationException(
                "Pendiente de implementar en la fase de autenticación (RF-13)");
    }
}
