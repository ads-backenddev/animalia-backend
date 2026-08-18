package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.LoginRequest;
import com.rescateanimal.backend.dto.request.RecuperarPasswordRequest;
import com.rescateanimal.backend.dto.request.RegistroCiudadanoRequest;
import com.rescateanimal.backend.dto.request.RegistroProtectoraRequest;
import com.rescateanimal.backend.dto.request.RestablecerPasswordRequest;
import com.rescateanimal.backend.dto.response.LoginResponse;
import com.rescateanimal.backend.dto.response.ProtectoraResponse;
import com.rescateanimal.backend.dto.response.UsuarioResponse;
import com.rescateanimal.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Registro, login, logout y recuperación de contraseña
 * (RF-01, RF-13, RF-16, RF-17).
 *
 * Todas las rutas son públicas salvo el logout, que necesita el token que va a
 * invalidar. Este controller solo valida la entrada y delega en AuthService.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String PREFIJO_BEARER = "Bearer ";

    private final AuthService authService;

    /** POST /api/auth/registro/ciudadano -> 201, 400, 409 */
    @PostMapping("/registro/ciudadano")
    public ResponseEntity<UsuarioResponse> registrarCiudadano(
            @Valid @RequestBody RegistroCiudadanoRequest peticion,
            UriComponentsBuilder uriBuilder) {

        UsuarioResponse creado = authService.registrarCiudadano(peticion);
        // Apunta a /api/usuarios/me y no a /api/usuarios/{id}: ese endpoint se
        // descartó por ser superficie de enumeración (docs/api-rest.md §15), así
        // que un Location hacia él devolvería 404.
        URI ubicacion = uriBuilder.path("/api/usuarios/me").build().toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    /** POST /api/auth/registro/protectora -> 201 con estado PENDIENTE, 400, 409 */
    @PostMapping("/registro/protectora")
    public ResponseEntity<ProtectoraResponse> registrarProtectora(
            @Valid @RequestBody RegistroProtectoraRequest peticion,
            UriComponentsBuilder uriBuilder) {

        ProtectoraResponse creada = authService.registrarProtectora(peticion);
        URI ubicacion = uriBuilder.path("/api/protectoras/{id}").buildAndExpand(creada.getId()).toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    /** POST /api/auth/login -> 200, 400, 401, 403 */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest peticion) {
        return ResponseEntity.ok(authService.login(peticion));
    }

    /**
     * POST /api/auth/logout -> 204, 401. Invalida el token de la cabecera (RF-16).
     *
     * La cabecera es obligatoria y la ruta exige autenticación en SecurityConfig:
     * sin token no hay nada que invalidar, y el contrato documenta un 401 para
     * ese caso.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String cabecera) {

        if (cabecera.startsWith(PREFIJO_BEARER)) {
            authService.logout(cabecera.substring(PREFIJO_BEARER.length()).trim());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/auth/recuperar-password -> 204 siempre (RF-17).
     *
     * Responde igual exista o no el email, para no revelar qué cuentas están
     * registradas.
     */
    @PostMapping("/recuperar-password")
    public ResponseEntity<Void> recuperarPassword(
            @Valid @RequestBody RecuperarPasswordRequest peticion) {

        authService.solicitarRecuperacion(peticion);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/auth/restablecer-password -> 204, 400 */
    @PostMapping("/restablecer-password")
    public ResponseEntity<Void> restablecerPassword(
            @Valid @RequestBody RestablecerPasswordRequest peticion) {

        authService.restablecerPassword(peticion);
        return ResponseEntity.noContent().build();
    }
}
