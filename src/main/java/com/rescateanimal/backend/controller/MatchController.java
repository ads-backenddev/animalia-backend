package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.CrearMatchRequest;
import com.rescateanimal.backend.dto.response.LimiteMatchResponse;
import com.rescateanimal.backend.dto.response.MatchResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.MatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Deslizamientos sobre publicaciones y consulta del límite diario
 * (RF-08, RF-15, RF-24).
 */
@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    /**
     * POST /api/matches -> 201, 400, 401, 404, 409, 429
     *
     * Los dos sentidos del swipe entran por aquí: interesado=true es un match
     * (abre chat y consume cuota) e interesado=false un descarte. El 429 llega
     * con el límite y la hora de reinicio en el cuerpo.
     */
    @PostMapping
    public ResponseEntity<MatchResponse> deslizar(
            @Valid @RequestBody CrearMatchRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        MatchResponse resultado = matchService.deslizar(peticion, usuario.getId());
        return ResponseEntity.status(201).body(resultado);
    }

    /** GET /api/matches/mios -> 200, 401. Matches del usuario autenticado (RF-08). */
    @GetMapping("/mios")
    public ResponseEntity<List<MatchResponse>> listarMisMatches(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(matchService.listarMisMatches(usuario.getId()));
    }

    /** GET /api/matches/limite -> 200, 401. Estado de la cuota diaria (RF-24). */
    @GetMapping("/limite")
    public ResponseEntity<LimiteMatchResponse> consultarLimite(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(matchService.consultarLimite(usuario.getId()));
    }
}
