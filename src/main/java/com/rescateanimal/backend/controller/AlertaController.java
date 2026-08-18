package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.CrearAlertaRequest;
import com.rescateanimal.backend.dto.response.AlertaMapaResponse;
import com.rescateanimal.backend.dto.response.AlertaResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AlertaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Alertas de mascota perdida (RF-03, RF-04, RF-14, RF-25).
 *
 * Todos los endpoints requieren JWT: lo impone SecurityConfig, que exige
 * autenticación para cualquier ruta fuera de /api/auth. Este controller solo
 * valida la entrada y delega en AlertaService.
 */
@RestController
@RequestMapping("/api/alertas")
@RequiredArgsConstructor
public class AlertaController {

    private final AlertaService alertaService;

    /**
     * POST /api/alertas -> 201, 400, 401, 409
     *
     * El 409 lo produce la detección de duplicados de RF-25, y su cuerpo incluye
     * la alerta ya existente para poder redirigir al ciudadano.
     */
    @PostMapping
    public ResponseEntity<AlertaResponse> publicar(
            @Valid @RequestBody CrearAlertaRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario,
            UriComponentsBuilder uriBuilder) {

        AlertaResponse creada = alertaService.publicar(peticion, usuario.getId());
        URI ubicacion = uriBuilder.path("/api/alertas/{id}").buildAndExpand(creada.getId()).toUri();
        return ResponseEntity.created(ubicacion).body(creada);
    }

    /**
     * GET /api/alertas/mapa -> 200, 400, 401
     *
     * Alertas ACTIVA geolocalizadas (RF-04). Los tres parámetros acotan el mapa
     * al área visible y se indican los tres o ninguno; sin ellos devuelve todas
     * las alertas activas.
     */
    @GetMapping("/mapa")
    public ResponseEntity<List<AlertaMapaResponse>> listarParaMapa(
            @RequestParam(name = "lat", required = false) Double latitud,
            @RequestParam(name = "lng", required = false) Double longitud,
            @RequestParam(name = "radio", required = false) Double radioMetros) {

        return ResponseEntity.ok(alertaService.listarParaMapa(latitud, longitud, radioMetros));
    }

    /** GET /api/alertas/mis-alertas -> 200. Alertas del usuario autenticado. */
    @GetMapping("/mis-alertas")
    public ResponseEntity<List<AlertaResponse>> listarMisAlertas(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(alertaService.listarMisAlertas(usuario.getId()));
    }

    /**
     * PUT /api/alertas/{id}/cerrar -> 200, 401, 403, 404
     * Marca la mascota como encontrada y la retira del mapa (RF-14).
     */
    @PutMapping("/{id}/cerrar")
    public ResponseEntity<AlertaResponse> cerrar(
            @PathVariable Long id,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(alertaService.cerrar(id, usuario.getId()));
    }
}
