package com.rescateanimal.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filtro que autentica cada petición a partir del JWT de la cabecera
 * Authorization (RF-13).
 *
 * Esqueleto: por ahora deja pasar la petición sin tocarla. En la siguiente fase
 * leerá el token, lo validará con JwtService, cargará el usuario con
 * UserDetailsServiceImpl y poblará el SecurityContext.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Sin implementar: se limita a continuar la cadena de filtros.
        filterChain.doFilter(request, response);
    }
}
