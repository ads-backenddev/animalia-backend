package com.rescateanimal.backend.security;

import com.rescateanimal.backend.service.TokenRevocadoService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Valida el JWT antes de aceptar una conexión WebSocket (docs/api-rest.md §13).
 *
 * Es lo que impide que un anónimo se suscriba a /topic/mapa y vea en tiempo real
 * la ubicación de todas las mascotas perdidas. Sin este filtro, abrir la ruta
 * /ws en SecurityConfig dejaría el canal completamente público.
 *
 * El token se acepta por parámetro de consulta además de por cabecera porque la
 * API WebSocket de los navegadores no permite añadir cabeceras al handshake.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    /** Clave con la que el usuario autenticado queda disponible para la sesión. */
    public static final String ATRIBUTO_USUARIO = "usuarioAutenticado";

    private static final String CABECERA = "Authorization";
    private static final String PREFIJO = "Bearer ";
    private static final String PARAMETRO = "token";

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;
    private final TokenRevocadoService tokenRevocadoService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {

        String token = extraerToken(request);
        if (token == null) {
            return rechazar(response, "sin token");
        }

        try {
            Claims claims = jwtService.validarYExtraer(token, JwtService.TIPO_ACCESO);

            if (tokenRevocadoService.estaRevocado(jwtService.getJti(claims))) {
                return rechazar(response, "token revocado");
            }

            UsuarioPrincipal usuario =
                    (UsuarioPrincipal) userDetailsService.loadUserByUsername(jwtService.getEmail(claims));

            if (!usuario.isEnabled()) {
                return rechazar(response, "cuenta desactivada");
            }
            if (!usuario.tokenEmitidoSigueVigente(jwtService.getEmitidoEn(claims))) {
                return rechazar(response, "token anterior al cambio de contraseña");
            }

            // Queda accesible desde los handlers de mensajes de la sesión.
            attributes.put(ATRIBUTO_USUARIO, usuario);
            log.debug("Handshake WebSocket aceptado para el usuario {}", usuario.getId());
            return true;

        } catch (RuntimeException e) {
            return rechazar(response, e.getMessage());
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Nada que hacer: la autorización se resuelve antes del handshake.
    }

    private boolean rechazar(ServerHttpResponse response, String motivo) {
        // No se registra el token ni el email: solo el motivo (RNF-10).
        log.debug("Handshake WebSocket rechazado: {}", motivo);
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    private String extraerToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            String cabecera = servlet.getServletRequest().getHeader(CABECERA);
            if (cabecera != null && cabecera.startsWith(PREFIJO)) {
                String token = cabecera.substring(PREFIJO.length()).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            }
            String parametro = servlet.getServletRequest().getParameter(PARAMETRO);
            if (parametro != null && !parametro.isBlank()) {
                return parametro;
            }
        }
        return null;
    }
}
