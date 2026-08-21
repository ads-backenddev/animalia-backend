package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.BloquearUsuarioRequest;
import com.rescateanimal.backend.dto.request.RechazarProtectoraRequest;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ProtectoraAdminResponse;
import com.rescateanimal.backend.dto.response.ReporteResponse;
import com.rescateanimal.backend.dto.response.UsuarioAdminResponse;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Panel de administración (RF-11): bloqueo de usuarios, verificación de
 * protectoras, revisión de reportes y retirada de publicaciones.
 *
 * EL @PreAuthorize VA EN LA CLASE, no método a método, igual que en
 * ProtectoraPanelController. Aquí importa todavía más: son ocho endpoints que
 * exigen exactamente el mismo rol, y son los ocho más peligrosos de la API. Con
 * la anotación repetida ocho veces, un noveno endpoint que naciera sin ella
 * quedaría abierto a cualquier autenticado y nada lo delataría. En la clase,
 * todo lo que se añada al panel nace protegido.
 *
 * ADMIN ES EL PERMISO COMPLETO. A diferencia del panel de protectora, no hay una
 * segunda comprobación en el service: el administrador no tiene un subconjunto
 * de datos "suyos" sobre el que operar, su ámbito es la plataforma entera. La
 * única regla añadida es que nadie puede bloquearse a sí mismo, y esa vive en
 * AdminService porque depende de a quién se está bloqueando.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // ---------------------------------------------------------------------
    // Usuarios
    // ---------------------------------------------------------------------

    /**
     * GET /api/admin/usuarios -> 200, 400, 401, 403
     *
     * Listado paginado con dos filtros opcionales y combinables: `rol` y
     * `activo`. Omitir `activo` devuelve bloqueados y no bloqueados; no es lo
     * mismo que `activo=false`.
     *
     * El 400 lo produce un `rol` que no sea del enum, que Spring rechaza al
     * convertir el parámetro.
     */
    @GetMapping("/usuarios")
    public ResponseEntity<PaginaResponse<UsuarioAdminResponse>> listarUsuarios(
            @RequestParam(required = false) Rol rol,
            @RequestParam(required = false) Boolean activo,
            @PageableDefault(size = 20) Pageable paginacion) {

        return ResponseEntity.ok(adminService.listarUsuarios(rol, activo, paginacion));
    }

    /**
     * PUT /api/admin/usuarios/{id}/bloquear -> 200, 400, 401, 403, 404
     *
     * El motivo es obligatorio. Es PUT y no PATCH por lo mismo que el marcado de
     * adoptado: fija el estado de la cuenta y repetirlo la deja igual.
     *
     * El 403 con código AUTOBLOQUEO es intentar bloquearse a uno mismo; el 403
     * sin cuerpo propio, no tener el rol.
     */
    @PutMapping("/usuarios/{id}/bloquear")
    public ResponseEntity<UsuarioAdminResponse> bloquear(
            @PathVariable Long id,
            @Valid @RequestBody BloquearUsuarioRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal administrador) {

        return ResponseEntity.ok(
                adminService.bloquear(id, peticion.getMotivo(), administrador.getId()));
    }

    /**
     * PUT /api/admin/usuarios/{id}/desbloquear -> 200, 401, 403, 404
     *
     * Sin cuerpo: devolver el acceso no necesita justificarse igual que quitarlo.
     */
    @PutMapping("/usuarios/{id}/desbloquear")
    public ResponseEntity<UsuarioAdminResponse> desbloquear(
            @PathVariable Long id,
            @AuthenticationPrincipal UsuarioPrincipal administrador) {

        return ResponseEntity.ok(adminService.desbloquear(id, administrador.getId()));
    }

    // ---------------------------------------------------------------------
    // Verificación de protectoras
    // ---------------------------------------------------------------------

    /**
     * GET /api/admin/protectoras/pendientes -> 200, 401, 403
     *
     * Cola de verificación, más antiguas primero. No pagina: es una bandeja de
     * trabajo que se consume entera.
     */
    @GetMapping("/protectoras/pendientes")
    public ResponseEntity<List<ProtectoraAdminResponse>> listarPendientes() {
        return ResponseEntity.ok(adminService.listarPendientes());
    }

    /** PUT /api/admin/protectoras/{id}/aprobar -> 200, 401, 403, 404 */
    @PutMapping("/protectoras/{id}/aprobar")
    public ResponseEntity<ProtectoraAdminResponse> aprobar(
            @PathVariable Long id,
            @AuthenticationPrincipal UsuarioPrincipal administrador) {

        return ResponseEntity.ok(adminService.aprobarProtectora(id, administrador.getId()));
    }

    /**
     * PUT /api/admin/protectoras/{id}/rechazar -> 200, 400, 401, 403, 404
     *
     * El motivo es obligatorio. Vale tanto para rechazar una solicitud pendiente
     * como para retirar una verificación ya concedida.
     */
    @PutMapping("/protectoras/{id}/rechazar")
    public ResponseEntity<ProtectoraAdminResponse> rechazar(
            @PathVariable Long id,
            @Valid @RequestBody RechazarProtectoraRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal administrador) {

        return ResponseEntity.ok(
                adminService.rechazarProtectora(id, peticion.getMotivo(), administrador.getId()));
    }

    // ---------------------------------------------------------------------
    // Reportes
    // ---------------------------------------------------------------------

    /**
     * GET /api/admin/reportes -> 200, 401, 403
     *
     * Reportes PENDIENTE, más antiguos primero. Devuelve lista vacía hasta que el
     * bloque 9 permita crear reportes (RF-26).
     */
    @GetMapping("/reportes")
    public ResponseEntity<List<ReporteResponse>> listarReportesPendientes() {
        return ResponseEntity.ok(adminService.listarReportesPendientes());
    }

    // ---------------------------------------------------------------------
    // Publicaciones
    // ---------------------------------------------------------------------

    /**
     * DELETE /api/admin/publicaciones/{id}?tipo=ALERTA|AVISTAMIENTO|ADOPCION
     * -> 204, 400, 401, 403, 404
     *
     * EL PARÁMETRO `tipo` ES OBLIGATORIO, y no es opcional por diseño sino por
     * necesidad: el id de una publicación solo es único dentro de su tabla, así
     * que el 7 existe a la vez como alerta, como avistamiento y como animal. Sin
     * el tipo, esta ruta no puede saber qué se le pide retirar. Es el mismo par
     * (tipo, id) que guarda REPORTE, de modo que el administrador puede pasar
     * directamente lo que le llega de GET /api/admin/reportes.
     *
     * DELETE en el verbo, pero no borra: la publicación pasa a su estado terminal
     * —ELIMINADA, ELIMINADO o RETIRADO según la tabla— y deja de mostrarse,
     * conservando los chats que colgaran de ella. Es el mismo criterio que
     * DELETE /api/alertas/{id} y DELETE /api/adopcion/{id}.
     *
     * Devuelve 204 y es idempotente: retirar lo ya retirado responde igual.
     */
    @DeleteMapping("/publicaciones/{id}")
    public ResponseEntity<Void> retirarPublicacion(
            @PathVariable Long id,
            @RequestParam TipoContenido tipo,
            @AuthenticationPrincipal UsuarioPrincipal administrador) {

        adminService.retirarPublicacion(tipo, id, administrador.getId());
        return ResponseEntity.noContent().build();
    }
}
