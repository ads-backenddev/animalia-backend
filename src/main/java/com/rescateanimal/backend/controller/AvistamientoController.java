package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.response.AvistamientoResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AvistamientoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Registro de avistamientos (RF-21).
 *
 * El feed de swipe ya no vive aquí: es de cada alerta y solo para su dueño,
 * en GET /api/alertas/{id}/avistamientos/feed (AlertaController). El feed
 * global que había en /api/avistamientos/feed se retiró con ese cambio.
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
}
