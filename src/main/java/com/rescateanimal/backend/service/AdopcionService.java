package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica del catálogo de adopción: publicación y gestión de animales por la
 * protectora, filtros y confirmación de adopción (RF-07, RF-20, RF-22).
 *
 * Esqueleto: sin métodos implementados todavía.
 */
@Service
@RequiredArgsConstructor
public class AdopcionService {

    private final AnimalAdopcionRepository animalAdopcionRepository;
}
