package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.MatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matches sobre avistamientos y adopciones, y consulta del límite diario
 * (RF-08, RF-09, RF-24).
 *
 * Esqueleto: sin endpoints implementados todavía.
 */
@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;
}
