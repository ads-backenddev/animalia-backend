package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ResumenPanelResponse;
import com.rescateanimal.backend.dto.response.SolicitudAdopcionResponse;
import com.rescateanimal.backend.dto.response.ValoracionesPanelResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.ProtectoraPanelService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Panel de gestión de la protectora autenticada (RF-10): sus animales, quién ha
 * querido contactar por ellos, sus valoraciones y los contadores de la pantalla
 * principal.
 *
 * TODO EL PANEL ES SOBRE UNO MISMO. Ningún endpoint recibe un id de protectora:
 * los cuatro parten del usuario del token y el service resuelve la suya. Por eso
 * no hay ningún caso de "ver el panel de otra protectora" que rechazar — no se
 * puede ni pedir—, y por eso ninguna ruta lleva {id}.
 *
 * EL @PreAuthorize VA EN LA CLASE, no en cada método, y es a propósito: los
 * cuatro endpoints exigen exactamente el mismo rol, y ponerlo cuatro veces solo
 * abre la puerta a que un quinto endpoint nazca sin él. Aquí cubre por
 * construcción todo lo que se añada al panel. Es la diferencia con
 * AnimalAdopcionController, donde conviven endpoints con rol y endpoints
 * abiertos a cualquier autenticado, y la anotación tiene que ir método a método.
 *
 * EL ROL NO ES SUFICIENTE. Tener rol PROTECTORA no significa estar verificada:
 * una PENDIENTE o una RECHAZADA lo tienen igual. Que además esté ACTIVA lo
 * comprueba ProtectoraPanelService en cada petición, porque depende de una fila
 * que puede cambiar después de emitirse el token y ninguna anotación puede
 * verla. Responde 403 con PROTECTORA_PENDIENTE o PROTECTORA_RECHAZADA.
 */
@RestController
@RequestMapping("/api/protectoras/panel")
@PreAuthorize("hasRole('PROTECTORA')")
@RequiredArgsConstructor
public class ProtectoraPanelController {

    private final ProtectoraPanelService protectoraPanelService;

    /**
     * GET /api/protectoras/panel/animales -> 200, 401, 403
     *
     * Todos los animales de la protectora en cualquier estado, a diferencia de
     * GET /api/adopcion, que solo enseña los DISPONIBLE. Paginado, más recientes
     * primero.
     */
    @GetMapping("/animales")
    public ResponseEntity<PaginaResponse<AnimalAdopcionResponse>> animales(
            @AuthenticationPrincipal UsuarioPrincipal usuario,
            @PageableDefault(size = 20) Pageable paginacion) {

        return ResponseEntity.ok(protectoraPanelService.animales(usuario.getId(), paginacion));
    }

    /**
     * GET /api/protectoras/panel/solicitudes -> 200, 401, 403
     *
     * Los ciudadanos que han mostrado interés por alguno de sus animales, más
     * recientes primero. Nunca incluye descartes.
     *
     * No pagina, igual que los feeds: es la bandeja de trabajo de la protectora
     * y se consume entera.
     */
    @GetMapping("/solicitudes")
    public ResponseEntity<List<SolicitudAdopcionResponse>> solicitudes(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(protectoraPanelService.solicitudes(usuario.getId()));
    }

    /**
     * GET /api/protectoras/panel/valoraciones -> 200, 401, 403
     *
     * Valoraciones recibidas y su media, calculada sobre ellas y no sobre
     * ninguna columna guardada (RF-23). Las alimenta
     * PUT /api/protectoras/{id}/valoracion.
     */
    @GetMapping("/valoraciones")
    public ResponseEntity<ValoracionesPanelResponse> valoraciones(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(protectoraPanelService.valoraciones(usuario.getId()));
    }

    /**
     * GET /api/protectoras/panel/resumen -> 200, 401, 403
     *
     * Contadores para la pantalla principal, en una sola petición y sin traerse
     * ningún listado.
     */
    @GetMapping("/resumen")
    public ResponseEntity<ResumenPanelResponse> resumen(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(protectoraPanelService.resumen(usuario.getId()));
    }
}
