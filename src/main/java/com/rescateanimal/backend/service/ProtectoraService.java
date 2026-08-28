package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.ProtectoraRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica de protectoras: directorio, contacto con la protectora local y
 * valoraciones de 1 a 5 estrellas (RF-06, RF-23).
 *
 * Esqueleto: RF-06 —el directorio, el detalle y el contacto con la protectora
 * local— sigue sin implementar.
 *
 * LAS VALORACIONES YA NO PASAN POR AQUÍ, y conviene decirlo porque el nombre de
 * esta clase lo sugiere: emitirlas y leer la media es ValoracionService, con sus
 * propias reglas (RF-23). Cuando RF-06 se construya, el detalle de una protectora
 * tomará la media de allí, no la calculará por su cuenta.
 *
 * El panel de gestión de RF-10 vive en ProtectoraPanelService, no aquí.
 */
@Service
@RequiredArgsConstructor
public class ProtectoraService {

    private final ProtectoraRepository protectoraRepository;
}
