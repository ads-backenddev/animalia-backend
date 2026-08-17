package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.ProtectoraService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Directorio de protectoras, contacto, valoraciones y panel de gestión
 * (RF-06, RF-10, RF-23).
 *
 * Esqueleto: sin endpoints implementados todavía. El panel llevará
 * @PreAuthorize con el rol PROTECTORA.
 */
@RestController
@RequestMapping("/api/protectoras")
@RequiredArgsConstructor
public class ProtectoraController {

    private final ProtectoraService protectoraService;
}
