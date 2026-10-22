package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.NotificacionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.NotificacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listado y marcado de las notificaciones propias (RF-05, docs/api-rest.md §12).
 *
 * Es la forma de leerlas mientras no exista la entrega en vivo: ni el canal
 * /user/queue/notificaciones ni la push de RF-05 están construidos, y sin este
 * listado los avisos se guardaban sin que nadie pudiera verlos.
 */
@RestController
@RequestMapping("/api/notificaciones")
@RequiredArgsConstructor
public class NotificacionController {

    private final NotificacionService notificacionService;

    /**
     * GET /api/notificaciones -> 200, 400, 401
     *
     * Las del usuario del token, más recientes primero, en páginas de como mucho
     * 100. Con leida=false solo las pendientes, con leida=true solo las leídas.
     */
    @GetMapping
    public ResponseEntity<PaginaResponse<NotificacionResponse>> listar(
            @RequestParam(required = false) Boolean leida,
            @PageableDefault(size = 20) Pageable paginacion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(notificacionService.listar(usuario.getId(), leida, paginacion));
    }

    /**
     * PATCH /api/notificaciones/{id}/leida -> 204, 400, 401, 403, 404
     *
     * Marca una notificación propia como leída. Repetirlo no cambia nada y
     * responde igual. 403 si es de otro usuario; 404 si no existe.
     */
    @PatchMapping("/{id}/leida")
    public ResponseEntity<Void> marcarLeida(@PathVariable Long id,
                                            @AuthenticationPrincipal UsuarioPrincipal usuario) {
        notificacionService.marcarLeida(id, usuario.getId());
        return ResponseEntity.noContent().build();
    }
}
