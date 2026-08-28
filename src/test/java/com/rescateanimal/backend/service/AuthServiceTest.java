package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.LoginRequest;
import com.rescateanimal.backend.dto.request.RecuperarPasswordRequest;
import com.rescateanimal.backend.dto.request.RegistroCiudadanoRequest;
import com.rescateanimal.backend.dto.request.RegistroProtectoraRequest;
import com.rescateanimal.backend.dto.request.RestablecerPasswordRequest;
import com.rescateanimal.backend.dto.response.ProtectoraResponse;
import com.rescateanimal.backend.dto.response.UsuarioResponse;
import com.rescateanimal.backend.exception.CredencialesInvalidasException;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.exception.EmailYaRegistradoException;
import com.rescateanimal.backend.exception.TokenInvalidoException;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.security.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas de la lógica de registro, login, logout y recuperación de contraseña
 * (RF-01, RF-13, RF-16, RF-17).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private ProtectoraRepository protectoraRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private TokenRevocadoService tokenRevocadoService;
    @Mock
    private EmailService emailService;

    /**
     * Va de verdad, no simulado, y por eso es @Spy y no @Mock: lo que importa de
     * él es el email concreto que produce —determinista e irreversible—, y un
     * mock devolviendo null solo diría que se le llama. @InjectMocks inyecta un
     * spy igual que un mock.
     */
    @Spy
    private AnonimizacionService anonimizacionService = new AnonimizacionService();

    @InjectMocks
    private AuthService authService;

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Registro (RF-01)")
    class Registro {

        @Test
        @DisplayName("El ciudadano se guarda con la contraseña cifrada, nunca en claro")
        void guardaLaContrasenaCifrada() {
            RegistroCiudadanoRequest peticion = new RegistroCiudadanoRequest(
                    "Ana", "Ana@Test.es", "passw0rd123", "600111222", "Madrid", null);
            when(usuarioRepository.existsByEmail("ana@test.es")).thenReturn(false);
            when(passwordEncoder.encode("passw0rd123")).thenReturn("$2a$10$hash");
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));

            UsuarioResponse respuesta = authService.registrarCiudadano(peticion);

            ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
            verify(usuarioRepository).save(guardado.capture());
            assertThat(guardado.getValue().getPasswordHash()).isEqualTo("$2a$10$hash");
            assertThat(guardado.getValue().getPasswordHash()).isNotEqualTo("passw0rd123");
            assertThat(guardado.getValue().getRol()).isEqualTo(Rol.CIUDADANO);
            assertThat(guardado.getValue().getActivo()).isTrue();
            assertThat(respuesta.getRol()).isEqualTo(Rol.CIUDADANO);
        }

        @Test
        @DisplayName("El email se normaliza a minúsculas para que el login no dependa de cómo se teclee")
        void normalizaElEmail() {
            RegistroCiudadanoRequest peticion = new RegistroCiudadanoRequest(
                    "Ana", "  ANA@Test.ES  ", "passw0rd123", null, null, null);
            when(usuarioRepository.existsByEmail("ana@test.es")).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));

            UsuarioResponse respuesta = authService.registrarCiudadano(peticion);

            assertThat(respuesta.getEmail()).isEqualTo("ana@test.es");
        }

        @Test
        @DisplayName("Un email ya registrado se rechaza con la excepción que produce el 409")
        void rechazaEmailDuplicado() {
            RegistroCiudadanoRequest peticion = new RegistroCiudadanoRequest(
                    "Ana", "ana@test.es", "passw0rd123", null, null, null);
            when(usuarioRepository.existsByEmail("ana@test.es")).thenReturn(true);

            assertThatThrownBy(() -> authService.registrarCiudadano(peticion))
                    .isInstanceOf(EmailYaRegistradoException.class);

            verify(usuarioRepository, never()).save(any());
        }

        @Test
        @DisplayName("La protectora nace en estado PENDIENTE, a la espera del administrador")
        void laProtectoraNacePendiente() {
            RegistroProtectoraRequest peticion = new RegistroProtectoraRequest(
                    "Refugio", "refugio@test.es", "passw0rd123", null,
                    "Huellas", "C/ Mayor 1", "Madrid");
            when(usuarioRepository.existsByEmail("refugio@test.es")).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
            when(protectoraRepository.save(any(Protectora.class))).thenAnswer(i -> i.getArgument(0));

            ProtectoraResponse respuesta = authService.registrarProtectora(peticion);

            assertThat(respuesta.getEstado()).isEqualTo(EstadoProtectora.PENDIENTE);
            assertThat(respuesta.getUsuario().getRol()).isEqualTo(Rol.PROTECTORA);
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Login (RF-13)")
    class Login {

        @Test
        @DisplayName("Credenciales correctas devuelven token y datos del usuario")
        void devuelveTokenConCredencialesCorrectas() {
            Usuario usuario = usuarioActivo();
            when(usuarioRepository.findByEmail("ana@test.es")).thenReturn(Optional.of(usuario));
            when(passwordEncoder.matches("passw0rd123", "$2a$10$hash")).thenReturn(true);
            Instant caducidad = Instant.now().plusSeconds(3600);
            when(jwtService.generarTokenAcceso(any()))
                    .thenReturn(new JwtService.TokenAcceso("el-token", caducidad));

            var respuesta = authService.login(new LoginRequest("ana@test.es", "passw0rd123"));

            assertThat(respuesta.getToken()).isEqualTo("el-token");
            assertThat(respuesta.getTipo()).isEqualTo("Bearer");
            assertThat(respuesta.getExpiraEn()).isEqualTo(caducidad);
            assertThat(respuesta.getUsuario().getEmail()).isEqualTo("ana@test.es");
        }

        @Test
        @DisplayName("Una contraseña incorrecta produce el 401")
        void rechazaContrasenaIncorrecta() {
            when(usuarioRepository.findByEmail("ana@test.es")).thenReturn(Optional.of(usuarioActivo()));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> authService.login(new LoginRequest("ana@test.es", "mala")))
                    .isInstanceOf(CredencialesInvalidasException.class);
        }

        @Test
        @DisplayName("Con un email inexistente se cifra igualmente, para no delatar por tiempo qué cuentas existen")
        void gastaElMismoTiempoConEmailInexistente() {
            when(usuarioRepository.findByEmail("nadie@test.es")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new LoginRequest("nadie@test.es", "loquesea")))
                    .isInstanceOf(CredencialesInvalidasException.class);

            // La comparación contra el hash de descarte es lo que iguala los tiempos.
            verify(passwordEncoder).matches(eq("loquesea"), anyString());
        }

        @Test
        @DisplayName("Una cuenta bloqueada por el administrador no puede entrar (RF-11)")
        void rechazaCuentaBloqueada() {
            Usuario usuario = usuarioActivo();
            usuario.setActivo(false);
            when(usuarioRepository.findByEmail("ana@test.es")).thenReturn(Optional.of(usuario));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            assertThatThrownBy(() -> authService.login(new LoginRequest("ana@test.es", "passw0rd123")))
                    .isInstanceOf(CuentaNoAutorizadaException.class)
                    .extracting(e -> ((CuentaNoAutorizadaException) e).getCodigo())
                    .isEqualTo("CUENTA_BLOQUEADA");
        }

        @Test
        @DisplayName("Una protectora sin verificar no puede entrar hasta que el administrador la apruebe")
        void rechazaProtectoraPendiente() {
            Usuario usuario = usuarioActivo();
            usuario.setRol(Rol.PROTECTORA);
            Protectora protectora = new Protectora();
            protectora.setEstado(EstadoProtectora.PENDIENTE);
            when(usuarioRepository.findByEmail("ana@test.es")).thenReturn(Optional.of(usuario));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(protectoraRepository.findByUsuarioId(1L)).thenReturn(Optional.of(protectora));

            assertThatThrownBy(() -> authService.login(new LoginRequest("ana@test.es", "passw0rd123")))
                    .isInstanceOf(CuentaNoAutorizadaException.class)
                    .extracting(e -> ((CuentaNoAutorizadaException) e).getCodigo())
                    .isEqualTo("PROTECTORA_PENDIENTE");
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Recuperación de contraseña (RF-17)")
    class Recuperacion {

        @Test
        @DisplayName("Si el email no tiene cuenta no se envía ningún correo, pero tampoco se avisa")
        void noEnviaCorreoSiElEmailNoExiste() {
            when(usuarioRepository.findByEmail("nadie@test.es")).thenReturn(Optional.empty());

            authService.solicitarRecuperacion(new RecuperarPasswordRequest("nadie@test.es"));

            verify(emailService, never()).enviarEnlaceRecuperacion(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Con una cuenta existente se envía el enlace con su token")
        void enviaElEnlaceSiLaCuentaExiste() {
            when(usuarioRepository.findByEmail("ana@test.es")).thenReturn(Optional.of(usuarioActivo()));
            when(jwtService.generarTokenReset("ana@test.es")).thenReturn("token-reset");

            authService.solicitarRecuperacion(new RecuperarPasswordRequest("ana@test.es"));

            verify(emailService).enviarEnlaceRecuperacion("ana@test.es", "Ana", "token-reset");
        }

        @Test
        @DisplayName("Restablecer marca passwordChangedAt, que invalida las sesiones abiertas, y revoca el enlace")
        void restablecerCierraLasSesionesAbiertas() {
            Usuario usuario = usuarioActivo();
            Claims claims = claimsDeReset();
            when(jwtService.validarYExtraer("token-reset", JwtService.TIPO_RESET)).thenReturn(claims);
            when(jwtService.getJti(claims)).thenReturn("jti-reset");
            when(jwtService.getEmail(claims)).thenReturn("ana@test.es");
            when(jwtService.getEmitidoEn(claims)).thenReturn(Instant.now());
            when(jwtService.getExpiracion(claims)).thenReturn(Instant.now().plusSeconds(3600));
            when(tokenRevocadoService.estaRevocado("jti-reset")).thenReturn(false);
            when(usuarioRepository.findByEmail("ana@test.es")).thenReturn(Optional.of(usuario));
            when(passwordEncoder.encode("nuevaClave123")).thenReturn("$2a$10$nuevo");

            authService.restablecerPassword(
                    new RestablecerPasswordRequest("token-reset", "nuevaClave123"));

            assertThat(usuario.getPasswordHash()).isEqualTo("$2a$10$nuevo");
            assertThat(usuario.getPasswordChangedAt()).isNotNull();
            verify(tokenRevocadoService).revocar(eq("jti-reset"), any(Instant.class));
        }

        @Test
        @DisplayName("Un enlace de recuperación ya usado no vuelve a funcionar")
        void rechazaEnlaceYaUsado() {
            Claims claims = claimsDeReset();
            when(jwtService.validarYExtraer("token-reset", JwtService.TIPO_RESET)).thenReturn(claims);
            when(jwtService.getJti(claims)).thenReturn("jti-reset");
            when(tokenRevocadoService.estaRevocado("jti-reset")).thenReturn(true);

            assertThatThrownBy(() -> authService.restablecerPassword(
                    new RestablecerPasswordRequest("token-reset", "nuevaClave123")))
                    .isInstanceOf(TokenInvalidoException.class);

            verify(usuarioRepository, never()).save(any());
        }

        @Test
        @DisplayName("Una cuenta bloqueada tampoco puede cambiarse la contraseña")
        void rechazaCuentaBloqueada() {
            Usuario usuario = usuarioActivo();
            usuario.setActivo(false);
            Claims claims = claimsDeReset();
            when(jwtService.validarYExtraer("token-reset", JwtService.TIPO_RESET)).thenReturn(claims);
            when(jwtService.getJti(claims)).thenReturn("jti-reset");
            when(jwtService.getEmail(claims)).thenReturn("ana@test.es");
            when(tokenRevocadoService.estaRevocado("jti-reset")).thenReturn(false);
            when(usuarioRepository.findByEmail("ana@test.es")).thenReturn(Optional.of(usuario));

            assertThatThrownBy(() -> authService.restablecerPassword(
                    new RestablecerPasswordRequest("token-reset", "nuevaClave123")))
                    .isInstanceOf(CuentaNoAutorizadaException.class);
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Logout (RF-16)")
    class Logout {

        @Test
        @DisplayName("El logout revoca el token en curso")
        void revocaElTokenEnCurso() {
            Claims claims = claimsDeReset();
            Instant caducidad = Instant.now().plusSeconds(3600);
            when(jwtService.validarYExtraer("el-token", JwtService.TIPO_ACCESO)).thenReturn(claims);
            when(jwtService.getJti(claims)).thenReturn("jti-acceso");
            when(jwtService.getExpiracion(claims)).thenReturn(caducidad);

            authService.logout("el-token");

            verify(tokenRevocadoService).revocar("jti-acceso", caducidad);
        }

        @Test
        @DisplayName("Cerrar sesión con un token ya inválido no es un error para el cliente")
        void toleraTokenYaInvalido() {
            when(jwtService.validarYExtraer("caducado", JwtService.TIPO_ACCESO))
                    .thenThrow(new TokenInvalidoException("caducado"));

            authService.logout("caducado");

            verify(tokenRevocadoService, never()).revocar(anyString(), any());
        }

        @Test
        @DisplayName("Un logout sin token no hace nada")
        void toleraTokenVacio() {
            authService.logout("   ");

            verify(tokenRevocadoService, never()).revocar(anyString(), any());
        }
    }

    // ---------------------------------------------------------------------

    private Usuario usuarioActivo() {
        Usuario usuario = new Usuario();
        usuario.setId(1L);
        usuario.setNombre("Ana");
        usuario.setEmail("ana@test.es");
        usuario.setPasswordHash("$2a$10$hash");
        usuario.setRol(Rol.CIUDADANO);
        usuario.setActivo(true);
        return usuario;
    }

    /** Los claims son opacos para el service: solo los pasa a JwtService, que está simulado. */
    private Claims claimsDeReset() {
        return org.mockito.Mockito.mock(Claims.class);
    }
}
