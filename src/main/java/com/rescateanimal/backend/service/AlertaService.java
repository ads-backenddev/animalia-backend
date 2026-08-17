package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.AlertaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica de las alertas de mascota perdida: publicación, mapa en tiempo real,
 * cierre al encontrarse la mascota y detección de alertas duplicadas
 * (RF-03, RF-04, RF-14, RF-25).
 *
 * Esqueleto: sin métodos implementados todavía.
 */
@Service
@RequiredArgsConstructor
public class AlertaService {

    private final AlertaRepository alertaRepository;
}
