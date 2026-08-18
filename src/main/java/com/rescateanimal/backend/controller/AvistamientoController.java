package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.response.AvistamientoFeedResponse;
import com.rescateanimal.backend.dto.response.AvistamientoResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AvistamientoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Registro de avistamientos y feed para el swipe (RF-15, RF-21).
 *
 * Todos los endpoints requieren JWT: lo impone SecurityConfig, que exige
 * autenticación para cualquier ruta fuera de /api/auth.
 */
@RestController
@RequestMapping("/api/avistamientos")
@RequiredArgsConstructor
public class AvistamientoController {

    private final AvistamientoService avistamientoService;

    /**
     * POST /api/avistamientos -> 201, 400, 401, 404
     *
     * El 404 es que la alerta referenciada no existe; el 400 con código
     * ALERTA_NO_ACTIVA, que existe pero ya está cerrada.
     */
    @PostMapping
    public ResponseEntity<AvistamientoResponse> registrar(
            @Valid @RequestBody CrearAvistamientoRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario,
            UriComponentsBuilder uriBuilder) {

        AvistamientoResponse creado = avistamientoService.registrar(peticion, usuario.getId());
        URI ubicacion = uriBuilder.path("/api/avistamientos/{id}")
                .buildAndExpand(creado.getId()).toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    /**
     * GET /api/avistamientos/feed -> 200, 401
     * Tarjetas que el usuario todavía no ha deslizado (RF-15).
     */
    @GetMapping("/feed")
    public ResponseEntity<List<AvistamientoFeedResponse>> feed(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(avistamientoService.feed(usuario.getId()));
    }
}
