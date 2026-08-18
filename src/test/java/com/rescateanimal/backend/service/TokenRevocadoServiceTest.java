package com.rescateanimal.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de la lista de tokens invalidados antes de caducar, que sostiene el
 * logout (RF-16) y el uso único del enlace de recuperación (RF-17).
 */
class TokenRevocadoServiceTest {

    private TokenRevocadoService servicio;

    @BeforeEach
    void prepararServicio() {
        servicio = new TokenRevocadoService();
    }

    @Test
    @DisplayName("Un token revocado consta como tal hasta su caducidad")
    void reconoceTokenRevocado() {
        servicio.revocar("jti-1", Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(servicio.estaRevocado("jti-1")).isTrue();
    }

    @Test
    @DisplayName("Un token que nunca se revocó no consta")
    void noReconoceTokenDesconocido() {
        assertThat(servicio.estaRevocado("jti-desconocido")).isFalse();
    }

    @Test
    @DisplayName("Un token ya caducado deja de constar: no hace falta recordarlo")
    void olvidaTokenCaducado() {
        servicio.revocar("jti-viejo", Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(servicio.estaRevocado("jti-viejo")).isFalse();
    }

    @Test
    @DisplayName("Un jti nulo no rompe: un token sin identificador nunca está revocado")
    void toleraJtiNulo() {
        servicio.revocar(null, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(servicio.estaRevocado(null)).isFalse();
    }

    @Test
    @DisplayName("Revocar dos veces el mismo token es idempotente")
    void revocarDosVecesEsIdempotente() {
        Instant caducidad = Instant.now().plus(1, ChronoUnit.HOURS);
        servicio.revocar("jti-2", caducidad);
        servicio.revocar("jti-2", caducidad);

        assertThat(servicio.estaRevocado("jti-2")).isTrue();
    }
}
