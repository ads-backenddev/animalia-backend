package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.AdopcionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo de adopción: gestión de animales por la protectora, filtros y
 * confirmación de adopción (RF-07, RF-20, RF-22).
 *
 * Esqueleto: sin endpoints implementados todavía. Los endpoints de gestión
 * llevarán @PreAuthorize con el rol PROTECTORA.
 */
@RestController
@RequestMapping("/api/adopcion")
@RequiredArgsConstructor
public class AdopcionController {

    private final AdopcionService adopcionService;
}
