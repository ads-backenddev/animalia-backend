package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.ReporteRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lógica de administración: bloqueo de usuarios, verificación de protectoras,
 * revisión de reportes y eliminación de publicaciones (RF-11).
 *
 * Esqueleto: sin métodos implementados todavía.
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UsuarioRepository usuarioRepository;
    private final ProtectoraRepository protectoraRepository;
    private final ReporteRepository reporteRepository;
    private final AlertaRepository alertaRepository;
}
