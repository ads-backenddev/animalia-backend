package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.ActualizarAlertaRequest;
import com.rescateanimal.backend.dto.request.CrearAlertaRequest;
import com.rescateanimal.backend.dto.response.AlertaDetalleResponse;
import com.rescateanimal.backend.dto.response.AlertaMapaResponse;
import com.rescateanimal.backend.dto.response.AlertaResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AlertaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    /**
     * GET /api/alertas -> 200, 400, 401
     *
     * Listado paginado para navegar y buscar, no para el mapa —que ya recibe las
     * activas en tiempo real por /topic/mapa—. Por defecto solo ACTIVA; estado
     * admite también ENCONTRADA. Zona = los tres de lat, lng y radio, o ninguno.
     */
    @GetMapping
    public ResponseEntity<PaginaResponse<AlertaResponse>> listar(
            @RequestParam(required = false) String especie,
            @RequestParam(required = false) EstadoAlerta estado,
            @RequestParam(name = "lat", required = false) Double latitud,
            @RequestParam(name = "lng", required = false) Double longitud,
            @RequestParam(name = "radio", required = false) Double radioMetros,
            @PageableDefault(size = 20) Pageable paginacion) {

        return ResponseEntity.ok(
                alertaService.listar(especie, estado, latitud, longitud, radioMetros, paginacion));
    }

    @GetMapping("/mapa")
    public ResponseEntity<List<AlertaMapaResponse>> listarParaMapa(
            @RequestParam(name = "lat", required = false) Double latitud,
            @RequestParam(name = "lng", required = false) Double longitud,
            @RequestParam(name = "radio", required = false) Double radioMetros) {

        return ResponseEntity.ok(alertaService.listarParaMapa(latitud, longitud, radioMetros));
    }

    /**
     * GET /api/alertas/{id} -> 200, 401, 404
     *
     * 200 para cualquier alerta que haya existido, en el estado que esté; 404
     * solo si el id nunca existió. Una RETIRADA o ELIMINADA llega reducida.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AlertaDetalleResponse> detalle(@PathVariable Long id) {
        return ResponseEntity.ok(alertaService.detalle(id));
    }

    /**
     * PUT /api/alertas/{id} -> 200, 400, 401, 403, 404, 409
     *
     * Solo su autor y solo mientras siga ACTIVA. El 409 es RF-25: si la especie
     * o la ubicación nuevas la hacen duplicada de otra alerta.
     */
    @PutMapping("/{id}")
    public ResponseEntity<AlertaResponse> actualizar(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarAlertaRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(alertaService.actualizar(id, peticion, usuario.getId()));
    }

    /**
     * DELETE /api/alertas/{id} -> 204, 401, 403, 404
     *
     * Retira la alerta: pasa a RETIRADA, no se borra. Idempotente.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> retirar(@PathVariable Long id,
                                        @AuthenticationPrincipal UsuarioPrincipal usuario) {
        alertaService.retirar(id, usuario.getId());
        return ResponseEntity.noContent().build();
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
