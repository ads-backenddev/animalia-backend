package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.ValoracionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica de protectoras: directorio, contacto con la protectora local y
 * valoraciones de 1 a 5 estrellas (RF-06, RF-23).
 *
 * Esqueleto: sin métodos implementados todavía. Crear valoraciones es del
 * bloque 9; hasta entonces VALORACION no tiene quien escriba en ella.
 *
 * El panel de gestión de RF-10 vive en ProtectoraPanelService, no aquí.
 */
@Service
@RequiredArgsConstructor
public class ProtectoraService {

    private final ProtectoraRepository protectoraRepository;
    private final ValoracionRepository valoracionRepository;
}
