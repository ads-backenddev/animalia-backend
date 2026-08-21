package com.rescateanimal.backend.security;

import com.rescateanimal.backend.exception.TokenInvalidoException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.service.TokenRevocadoService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Seguridad del canal WebSocket.
 *
 * SecurityConfig abre /ws/** deliberadamente, porque el handshake no puede
 * llevar la cabecera Authorization desde un navegador. Eso hace que este
 * interceptor sea lo único que impide a un anónimo suscribirse a /topic/mapa y
 * ver en tiempo real dónde está cada mascota perdida: si deja de rechazar, el
 * canal queda público. De ahí que se pruebe cada motivo de rechazo por separado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtHandshakeInterceptorTest {

    private static final String TOKEN = "cabecera.cuerpo.firma";
    private static final String JTI = "jti-1";
    private static final String EMAIL = "ana@test.es";

    @Mock
    private JwtService jwtService;
    @Mock
    private UserDetailsServiceImpl userDetailsService;
    @Mock
    private TokenRevocadoService tokenRevocadoService;
    @Mock
    private Claims claims;

    @InjectMocks
    private JwtHandshakeInterceptor interceptor;

    private MockHttpServletRequest peticionServlet;
    private MockHttpServletResponse respuestaServlet;
    private Map<String, Object> atributos;

    @BeforeEach
    void prepararHandshake() {
        peticionServlet = new MockHttpServletRequest("GET", "/ws");
        respuestaServlet = new MockHttpServletResponse();
        atributos = new HashMap<>();

        when(jwtService.getJti(claims)).thenReturn(JTI);
        when(jwtService.getEmail(claims)).thenReturn(EMAIL);
        when(jwtService.getEmitidoEn(claims)).thenReturn(Instant.now());
    }

    @Nested
    @DisplayName("Rechazos")
    class Rechazos {

        @Test
        @DisplayName("Sin token -> handshake rechazado con 401")
        void sinTokenRechaza() {
            assertThat(ejecutar()).isFalse();
            assertThat(respuestaServlet.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(atributos).isEmpty();
        }

        @Test
        @DisplayName("Cabecera Authorization sin el prefijo Bearer -> rechazado")
        void cabeceraSinBearerRechaza() {
            peticionServlet.addHeader("Authorization", TOKEN);

            assertThat(ejecutar()).isFalse();
            assertThat(respuestaServlet.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("Token con firma inválida o caducado -> rechazado")
        void tokenInvalidoRechaza() {
            peticionServlet.setParameter("token", TOKEN);
            when(jwtService.validarYExtraer(TOKEN, JwtService.TIPO_ACCESO))
                    .thenThrow(new TokenInvalidoException("El token no es válido o ha caducado"));

            assertThat(ejecutar()).isFalse();
            assertThat(respuestaServlet.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(atributos).isEmpty();
        }

        @Test
        @DisplayName("Token de restablecimiento de contraseña -> rechazado, solo vale el de acceso")
        void tokenDeOtroTipoRechaza() {
            peticionServlet.setParameter("token", TOKEN);
            when(jwtService.validarYExtraer(TOKEN, JwtService.TIPO_ACCESO))
                    .thenThrow(new TokenInvalidoException("El token no es válido para esta operación"));

            assertThat(ejecutar()).isFalse();
            assertThat(respuestaServlet.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("Token revocado por un logout previo -> rechazado")
        void tokenRevocadoRechaza() {
            conTokenValido();
            when(tokenRevocadoService.estaRevocado(JTI)).thenReturn(true);

            assertThat(ejecutar()).isFalse();
            assertThat(respuestaServlet.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(atributos).isEmpty();
        }

        @Test
        @DisplayName("Cuenta bloqueada por el administrador -> rechazado aunque el token sea válido (RF-11)")
        void cuentaDesactivadaRechaza() {
            conTokenValido();
            when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(principal(false, null));

            assertThat(ejecutar()).isFalse();
            assertThat(respuestaServlet.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(atributos).isEmpty();
        }

        @Test
        @DisplayName("Token anterior al último cambio de contraseña -> rechazado (RF-17)")
        void tokenAnteriorAlCambioDePasswordRechaza() {
            conTokenValido();
            when(jwtService.getEmitidoEn(claims)).thenReturn(Instant.now().minusSeconds(3600));
            when(userDetailsService.loadUserByUsername(EMAIL))
                    .thenReturn(principal(true, Instant.now()));

            assertThat(ejecutar()).isFalse();
            assertThat(respuestaServlet.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("Usuario borrado después de emitir el token -> rechazado, no error 500")
        void usuarioInexistenteRechaza() {
            conTokenValido();
            when(userDetailsService.loadUserByUsername(EMAIL))
                    .thenThrow(new UsernameNotFoundException("no existe"));

            assertThat(ejecutar()).isFalse();
            assertThat(respuestaServlet.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
    }

    @Nested
    @DisplayName("Aceptaciones")
    class Aceptaciones {

        @Test
        @DisplayName("Token válido por parámetro -> aceptado y usuario disponible en la sesión")
        void tokenValidoPorParametroAcepta() {
            conTokenValido();
            when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(principal(true, null));

            assertThat(ejecutar()).isTrue();
            assertThat(atributos).containsKey(JwtHandshakeInterceptor.ATRIBUTO_USUARIO);
            assertThat(((UsuarioPrincipal) atributos.get(JwtHandshakeInterceptor.ATRIBUTO_USUARIO)).getId())
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("Token válido por cabecera Bearer -> aceptado")
        void tokenValidoPorCabeceraAcepta() {
            peticionServlet.addHeader("Authorization", "Bearer " + TOKEN);
            when(jwtService.validarYExtraer(TOKEN, JwtService.TIPO_ACCESO)).thenReturn(claims);
            when(tokenRevocadoService.estaRevocado(anyString())).thenReturn(false);
            when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(principal(true, null));

            assertThat(ejecutar()).isTrue();
            assertThat(atributos).containsKey(JwtHandshakeInterceptor.ATRIBUTO_USUARIO);
        }
    }

    private void conTokenValido() {
        peticionServlet.setParameter("token", TOKEN);
        when(jwtService.validarYExtraer(TOKEN, JwtService.TIPO_ACCESO)).thenReturn(claims);
        when(tokenRevocadoService.estaRevocado(anyString())).thenReturn(false);
    }

    private boolean ejecutar() {
        ServerHttpRequest peticion = new ServletServerHttpRequest(peticionServlet);
        ServerHttpResponse respuesta = new ServletServerHttpResponse(respuestaServlet);
        return interceptor.beforeHandshake(peticion, respuesta, null, atributos);
    }

    private static UsuarioPrincipal principal(boolean activo, Instant passwordChangedAt) {
        return new UsuarioPrincipal(1L, EMAIL, "$2a$10$hash", Rol.CIUDADANO, activo, passwordChangedAt);
    }
}
