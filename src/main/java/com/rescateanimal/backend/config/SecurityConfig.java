package com.rescateanimal.backend.config;

import org.springframework.context.annotation.Configuration;

/**
 * Configuración de Spring Security.
 *
 * Pendiente de configurar en la siguiente fase:
 * - SecurityFilterChain stateless (SessionCreationPolicy.STATELESS, sin sesiones).
 * - Registro de JwtAuthFilter antes del filtro de autenticación por usuario/contraseña.
 * - PasswordEncoder con BCrypt (RNF-03).
 * - Reglas de autorización por ruta y @EnableMethodSecurity para @PreAuthorize.
 */
@Configuration
public class SecurityConfig {
}
