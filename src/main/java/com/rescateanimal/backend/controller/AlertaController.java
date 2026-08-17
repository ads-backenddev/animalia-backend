package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.AlertaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertas de mascota perdida: CRUD, mapa, alertas propias y cierre
 * (RF-03, RF-04, RF-14, RF-25).
 *
 * Esqueleto: sin endpoints implementados todavía.
 */
@RestController
@RequestMapping("/api/alertas")
@RequiredArgsConstructor
public class AlertaController {

    private final AlertaService alertaService;
}
