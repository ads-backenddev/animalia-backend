package com.rescateanimal.backend.security;

import com.rescateanimal.backend.exception.TokenInvalidoException;
import com.rescateanimal.backend.service.TokenRevocadoService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Autentica cada petición a partir del JWT de la cabecera Authorization (RF-13).
 *
 * Si el token falta o no es válido, el filtro NO rechaza la petición: deja el
 * contexto de seguridad vacío y continúa. Quien decide si esa ruta exige
 * autenticación es SecurityConfig, de modo que los endpoints públicos siguen
 * funcionando sin token.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String CABECERA = "Authorization";
    private static final String PREFIJO = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;
    private final TokenRevocadoService tokenRevocadoService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extraerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            autenticar(token, request);
        }
        filterChain.doFilter(request, response);
    }

    private void autenticar(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.validarYExtraer(token, JwtService.TIPO_ACCESO);

            // Un token cuyo logout ya se ha ejecutado no vuelve a autenticar (RF-16).
            if (tokenRevocadoService.estaRevocado(jwtService.getJti(claims))) {
                log.debug("Token revocado rechazado para {}", request.getRequestURI());
                return;
            }

            // Se recarga el usuario en cada petición a propósito: así un bloqueo
            // del administrador (RF-11) surte efecto de inmediato, sin esperar a
            // que caduque el token que ya tenía emitido.
            UsuarioPrincipal usuario =
                    (UsuarioPrincipal) userDetailsService.loadUserByUsername(jwtService.getEmail(claims));

            if (!usuario.isEnabled()) {
                // Se registra el id y no el email: el email es dato personal (RNF-10).
                log.debug("Cuenta desactivada: se ignora el token del usuario {}", usuario.getId());
                return;
            }

            // Un token anterior al último cambio de contraseña ya no vale: es lo
            // que cierra todas las sesiones abiertas al restablecerla (RF-17).
            if (!usuario.tokenEmitidoSigueVigente(jwtService.getEmitidoEn(claims))) {
                log.debug("Token anterior al cambio de contraseña del usuario {}", usuario.getId());
                return;
            }

            UsernamePasswordAuthenticationToken autenticacion =
                    new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities());
            autenticacion.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(autenticacion);

        } catch (TokenInvalidoException | UsernameNotFoundException e) {
            // Solo se traga lo que significa "este token no sirve". Un fallo de
            // infraestructura (por ejemplo la base de datos caída) debe propagarse
            // y acabar en un 500, no disfrazarse de 401.
            log.debug("No se pudo autenticar la petición a {}: {}",
                    request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
        }
    }

    private String extraerToken(HttpServletRequest request) {
        String cabecera = request.getHeader(CABECERA);
        if (cabecera != null && cabecera.startsWith(PREFIJO)) {
            String token = cabecera.substring(PREFIJO.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
