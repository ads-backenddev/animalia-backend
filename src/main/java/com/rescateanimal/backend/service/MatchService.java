package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica de matches sobre avistamientos y adopciones, control del límite diario
 * y apertura del chat asociado (RF-08, RF-09, RF-12, RF-24).
 *
 * Esqueleto: sin métodos implementados todavía.
 */
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchRegistroRepository matchRegistroRepository;
    private final LimiteMatchDiarioRepository limiteMatchDiarioRepository;
    private final ChatRepository chatRepository;
}
