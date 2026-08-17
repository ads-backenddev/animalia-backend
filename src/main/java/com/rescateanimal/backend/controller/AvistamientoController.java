package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.AvistamientoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro de avistamientos y feed para el swipe (RF-15, RF-21).
 *
 * Esqueleto: sin endpoints implementados todavía.
 */
@RestController
@RequestMapping("/api/avistamientos")
@RequiredArgsConstructor
public class AvistamientoController {

    private final AvistamientoService avistamientoService;
}
