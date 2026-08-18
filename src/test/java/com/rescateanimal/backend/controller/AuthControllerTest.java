package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.LoginResponse;
import com.rescateanimal.backend.dto.response.UsuarioResponse;
import com.rescateanimal.backend.exception.CredencialesInvalidasException;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.exception.EmailYaRegistradoException;
import com.rescateanimal.backend.exception.TokenInvalidoException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.service.AuthService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas del contrato HTTP de /api/auth: que cada situación devuelve el código
 * documentado en docs/api-rest.md §2.
 *
 * Se prueba el controller junto a GlobalExceptionHandler, con AuthService
 * simulado: aquí no se verifica la lógica de negocio (eso es AuthServiceTest),
 * sino la traducción a códigos y cuerpos HTTP.
 *
 * Los filtros de seguridad se desactivan porque las reglas de acceso son de
 * SecurityConfig, no de este controller; JwtService, UserDetailsServiceImpl y
 * TokenRevocadoService se simulan solo para que JwtAuthFilter pueda construirse.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;
    @MockitoBean
    private TokenRevocadoService tokenRevocadoService;

    private static final String REGISTRO_VALIDO = """
            {"nombre":"Ana","email":"ana@test.es","password":"passw0rd123"}
            """;

    @Test
    @DisplayName("Registro correcto de ciudadano -> 201 con Location")
    void registroCiudadanoDevuelve201() throws Exception {
        when(authService.registrarCiudadano(any())).thenReturn(usuarioResponse());

        mockMvc.perform(post("/api/auth/registro/ciudadano")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/usuarios/me"))
                .andExpect(jsonPath("$.email").value("ana@test.es"))
                .andExpect(jsonPath("$.rol").value("CIUDADANO"));
    }

    @Test
    @DisplayName("La respuesta del registro nunca incluye el hash de la contraseña")
    void laRespuestaNoExponeElHash() throws Exception {
        when(authService.registrarCiudadano(any())).thenReturn(usuarioResponse());

        mockMvc.perform(post("/api/auth/registro/ciudadano")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("Email con formato inválido -> 400 detallando el campo")
    void emailInvalidoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/auth/registro/ciudadano")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Ana","email":"no-es-un-email","password":"passw0rd123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.email").exists());
    }

    @Test
    @DisplayName("Contraseña demasiado corta -> 400")
    void passwordCortaDevuelve400() throws Exception {
        mockMvc.perform(post("/api/auth/registro/ciudadano")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Ana","email":"ana@test.es","password":"corta"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.password").exists());
    }

    @Test
    @DisplayName("Email ya registrado -> 409")
    void emailDuplicadoDevuelve409() throws Exception {
        when(authService.registrarCiudadano(any()))
                .thenThrow(new EmailYaRegistradoException("Ya existe una cuenta con ese email"));

        mockMvc.perform(post("/api/auth/registro/ciudadano")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("EMAIL_DUPLICADO"));
    }

    @Test
    @DisplayName("JSON malformado -> 400, no 500")
    void jsonMalformadoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{esto no es json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Login correcto -> 200 con el token")
    void loginCorrectoDevuelve200() throws Exception {
        when(authService.login(any())).thenReturn(LoginResponse.builder()
                .token("el-token")
                .tipo("Bearer")
                .expiraEn(LocalDateTime.now().plusHours(24))
                .usuario(usuarioResponse())
                .build());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ana@test.es","password":"passw0rd123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("el-token"))
                .andExpect(jsonPath("$.tipo").value("Bearer"));
    }

    @Test
    @DisplayName("Credenciales incorrectas -> 401")
    void credencialesIncorrectasDevuelve401() throws Exception {
        when(authService.login(any()))
                .thenThrow(new CredencialesInvalidasException("Email o contraseña incorrectos"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ana@test.es","password":"mala1234"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
    }

    @Test
    @DisplayName("Protectora sin verificar -> 403 con su código, para que el cliente lo distinga")
    void protectoraPendienteDevuelve403() throws Exception {
        when(authService.login(any())).thenThrow(
                new CuentaNoAutorizadaException("PROTECTORA_PENDIENTE", "Pendiente de verificación"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"refugio@test.es","password":"passw0rd123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("PROTECTORA_PENDIENTE"));
    }

    @Test
    @DisplayName("Logout con token -> 204")
    void logoutDevuelve204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer el-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Recuperar contraseña -> 204 tanto si el email existe como si no")
    void recuperarDevuelveSiempre204() throws Exception {
        mockMvc.perform(post("/api/auth/recuperar-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"quiensea@test.es"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Token de restablecimiento inválido -> 400")
    void resetInvalidoDevuelve400() throws Exception {
        doThrow(new TokenInvalidoException("El token no es válido o ha caducado"))
                .when(authService).restablecerPassword(any());

        mockMvc.perform(post("/api/auth/restablecer-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"aa.bb.cc","nuevaPassword":"nuevaClave123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));
    }

    private UsuarioResponse usuarioResponse() {
        return UsuarioResponse.builder()
                .id(1L)
                .nombre("Ana")
                .email("ana@test.es")
                .rol(Rol.CIUDADANO)
                .idioma("es")
                .activo(true)
                .build();
    }
}
