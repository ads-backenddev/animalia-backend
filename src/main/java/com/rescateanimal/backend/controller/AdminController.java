package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel de administración: bloqueo de usuarios, verificación de protectoras,
 * revisión de reportes y borrado de publicaciones (RF-11).
 *
 * Esqueleto: sin endpoints implementados todavía. Todos los endpoints de este
 * controller llevarán @PreAuthorize con el rol ADMIN.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
}
