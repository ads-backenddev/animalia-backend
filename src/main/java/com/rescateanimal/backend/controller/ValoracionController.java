package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.ValorarProtectoraRequest;
import com.rescateanimal.backend.dto.response.ValoracionResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.ValoracionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Valoración de una protectora por parte de quien ha tratado con ella (RF-23).
 *
 * ES UN CONTROLLER APARTE DE ProtectoraController, que atiende el mismo prefijo
 * /api/protectoras, y la separación sigue el mismo criterio que ya separaba el
 * panel: se agrupa por a quién sirve cada cosa, no por el trozo de URL que
 * comparten. ProtectoraController es el directorio que un ciudadano consulta
 * (RF-06), ProtectoraPanelController es lo que una protectora ve de sí misma
 * (RF-10), y esto es la opinión que un tercero emite sobre ella. Tienen reglas
 * de acceso distintas y evolucionarán por motivos distintos.
 *
 * SIN @PreAuthorize DE CLASE, a diferencia del panel: aquí no hace falta ningún
 * rol concreto. Valorar está abierto a cualquier autenticado —SecurityConfig ya
 * exige token para todo lo que no sea /api/auth—, y el permiso que de verdad
 * importa no es el rol sino haber contactado antes con esa protectora, que
 * depende de dos filas de la base de datos y ninguna anotación puede verlo. Esa
 * comprobación vive en ValoracionService, que es donde puede consultarse.
 */
@RestController
@RequestMapping("/api/protectoras")
@RequiredArgsConstructor
public class ValoracionController {

    private final ValoracionService valoracionService;

    /**
     * PUT /api/protectoras/{protectoraId}/valoracion -> 200, 400, 401, 403, 404
     *
     * ES UN PUT Y NO UN POST porque la operación es idempotente por definición:
     * un usuario tiene como mucho una valoración sobre una protectora, y volver
     * a enviarla es cambiar de opinión, no añadir un segundo voto. Mandar la
     * misma puntuación dos veces deja exactamente el mismo estado, que es lo que
     * un PUT promete y un POST no.
     *
     * Devuelve 200 en los dos casos, se haya creado o actualizado. No se
     * distingue con un 201 a propósito: el recurso "mi valoración de esta
     * protectora" tiene una única URL que no cambia, así que no hay ningún
     * Location nuevo que anunciar, y obligar al cliente a distinguir los dos
     * códigos solo le complicaría la vida sin darle nada.
     *
     * 403 con SIN_INTERACCION_PREVIA si quien valora no ha contactado antes con
     * la protectora (RF-23), y 404 si la protectora no existe.
     */
    @PutMapping("/{protectoraId}/valoracion")
    public ResponseEntity<ValoracionResponse> valorar(
            @PathVariable Long protectoraId,
            @Valid @RequestBody ValorarProtectoraRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(valoracionService.valorar(
                protectoraId, usuario.getId(), peticion.getPuntuacion()));
    }
}
