package com.rescateanimal.backend.config;

import com.rescateanimal.backend.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configuración de Spring Security: API REST stateless autenticada con JWT.
 *
 * @EnableMethodSecurity habilita las anotaciones @PreAuthorize con las que se
 * protegen por rol los endpoints, tal y como exige el CLAUDE.md del proyecto.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /** BCrypt para el hash de las contraseñas (RNF-03). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Sin CSRF: no hay sesiones ni cookies, la credencial viaja en una
                // cabecera que un sitio de terceros no puede fijar.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())

                // Ninguna sesión de servidor: cada petición se autentica con su token.
                .sessionManagement(sesion ->
                        sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(rutas -> rutas
                        // El logout necesita el token que va a invalidar, así que exige
                        // autenticación (RF-16). Va ANTES del comodín de /api/auth/**:
                        // la primera regla que casa es la que manda.
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        // Registro, login y recuperación de contraseña son públicos por
                        // definición: son la puerta de entrada (RF-01, RF-13, RF-17).
                        .requestMatchers("/api/auth/**").permitAll()
                        // Sonda del healthcheck de Docker Compose (RNF-06).
                        .requestMatchers("/actuator/health").permitAll()
                        // Cerrado hasta que WebSocketConfig implemente la validación del
                        // JWT en el handshake (docs/api-rest.md §13). No abrir esta ruta
                        // antes que aquello: dejaría el chat y el mapa sin autenticar.
                        .requestMatchers("/ws/**").authenticated()
                        .anyRequest().authenticated())

                // Sin token válido se responde 401 seco, en vez del formulario de
                // login o el Basic que Spring pondría por defecto.
                .exceptionHandling(errores -> errores
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
