package com.rescateanimal.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Política CORS para el cliente Android y el panel web.
 *
 * SecurityConfig activa CORS con .cors(...), que busca precisamente el bean
 * CorsConfigurationSource definido aquí: sin él, la llamada quedaría declarada
 * pero no se aplicaría ninguna política.
 */
@Configuration
public class CorsConfig {

    private final List<String> origenesPermitidos;

    public CorsConfig(@Value("${app.cors.origenes:http://localhost:8081,http://localhost:5173}")
                      List<String> origenesPermitidos) {
        this.origenesPermitidos = origenesPermitidos;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuracion = new CorsConfiguration();

        // Lista explícita de orígenes, nunca "*": con allowCredentials activo el
        // comodín no es válido, y además abriría la API a cualquier sitio web.
        configuracion.setAllowedOrigins(origenesPermitidos);
        configuracion.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuracion.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language"));
        configuracion.setExposedHeaders(List.of("Location"));
        configuracion.setAllowCredentials(true);
        configuracion.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/api/**", configuracion);
        fuente.registerCorsConfiguration("/ws/**", configuracion);
        return fuente;
    }
}
