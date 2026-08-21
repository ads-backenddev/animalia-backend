package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.ProtectoraService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Directorio de protectoras, contacto y valoraciones (RF-06, RF-23).
 *
 * Esqueleto: sin endpoints implementados todavía.
 *
 * El panel de gestión de RF-10 NO está aquí: vive en ProtectoraPanelController,
 * bajo /api/protectoras/panel. Son dos cosas distintas y separarlas es
 * deliberado: esto es lo que un ciudadano ve DE una protectora, y aquello es lo
 * que una protectora ve DE SÍ MISMA. El reparto de permisos no se parece —aquí
 * casi todo es para cualquier autenticado, allí todo exige rol PROTECTORA y
 * estado ACTIVA—, y tenerlos juntos habría mezclado los dos criterios en un
 * mismo controller.
 */
@RestController
@RequestMapping("/api/protectoras")
@RequiredArgsConstructor
public class ProtectoraController {

    private final ProtectoraService protectoraService;
}
