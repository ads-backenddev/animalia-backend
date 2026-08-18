package com.rescateanimal.backend.security;

import com.rescateanimal.backend.exception.TokenInvalidoException;
import com.rescateanimal.backend.model.enums.Rol;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas del cifrado y validación de los JWT (RF-13, RF-17).
 *
 * Sin contexto de Spring: JwtService solo depende de tres valores de
 * configuración, así que se construye a mano.
 */
class JwtServiceTest {

    private static final String SECRETO = "secreto-de-pruebas-de-al-menos-32-caracteres-1234567890";
    private static final long UN_DIA_MS = 86_400_000L;
    private static final long UNA_HORA_MS = 3_600_000L;

    private JwtService jwtService;
    private UsuarioPrincipal usuario;

    @BeforeEach
    void prepararServicio() {
        jwtService = new JwtService(SECRETO, UN_DIA_MS, UNA_HORA_MS);
        jwtService.inicializarClave();
        usuario = new UsuarioPrincipal(7L, "ana@test.es", "$2a$10$hash", Rol.CIUDADANO, true, null);
    }

    @Test
    @DisplayName("El token de acceso conserva el email del usuario y caduca en el plazo configurado")
    void generaTokenDeAccesoValido() {
        JwtService.TokenAcceso generado = jwtService.generarTokenAcceso(usuario);

        Claims claims = jwtService.validarYExtraer(generado.token(), JwtService.TIPO_ACCESO);

        assertThat(jwtService.getEmail(claims)).isEqualTo("ana@test.es");
        assertThat(jwtService.getJti(claims)).isNotBlank();
        assertThat(jwtService.getEmitidoEn(claims)).isNotNull();
        assertThat(generado.expiraEn()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Un token de acceso no sirve para restablecer la contraseña")
    void tokenDeAccesoNoValeComoReset() {
        String token = jwtService.generarTokenAcceso(usuario).token();

        assertThatThrownBy(() -> jwtService.validarYExtraer(token, JwtService.TIPO_RESET))
                .isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    @DisplayName("Un token de restablecimiento no autentica peticiones a la API")
    void tokenDeResetNoValeComoAcceso() {
        String token = jwtService.generarTokenReset("ana@test.es");

        assertThatThrownBy(() -> jwtService.validarYExtraer(token, JwtService.TIPO_ACCESO))
                .isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    @DisplayName("Un token firmado con otra clave se rechaza")
    void rechazaTokenConFirmaAjena() {
        JwtService otroEmisor = new JwtService(
                "otro-secreto-distinto-de-al-menos-32-caracteres-123456", UN_DIA_MS, UNA_HORA_MS);
        otroEmisor.inicializarClave();
        String tokenAjeno = otroEmisor.generarTokenAcceso(usuario).token();

        assertThatThrownBy(() -> jwtService.validarYExtraer(tokenAjeno, JwtService.TIPO_ACCESO))
                .isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    @DisplayName("Un token manipulado se rechaza")
    void rechazaTokenManipulado() {
        String token = jwtService.generarTokenAcceso(usuario).token();
        String manipulado = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> jwtService.validarYExtraer(manipulado, JwtService.TIPO_ACCESO))
                .isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    @DisplayName("Un token caducado se rechaza")
    void rechazaTokenCaducado() {
        JwtService emisorCaducado = new JwtService(SECRETO, -1000L, UNA_HORA_MS);
        emisorCaducado.inicializarClave();
        String token = emisorCaducado.generarTokenAcceso(usuario).token();

        assertThatThrownBy(() -> jwtService.validarYExtraer(token, JwtService.TIPO_ACCESO))
                .isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    @DisplayName("Un secreto de menos de 32 caracteres impide arrancar la aplicación")
    void rechazaSecretoDemasiadoCorto() {
        JwtService conSecretoCorto = new JwtService("corto", UN_DIA_MS, UNA_HORA_MS);

        assertThatThrownBy(conSecretoCorto::inicializarClave)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
