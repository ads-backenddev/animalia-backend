package com.rescateanimal.backend.service;

import com.rescateanimal.backend.model.TokenRevocado;
import com.rescateanimal.backend.repository.TokenRevocadoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas de la lista de tokens invalidados antes de caducar, que sostiene el
 * logout (RF-16) y el uso único del enlace de recuperación (RF-17).
 *
 * Aquí se comprueba QUÉ le pide el service a su repositorio. Que la revocación
 * sobreviva de verdad a un reinicio lo comprueba
 * TokenRevocadoPersistenciaTest contra PostgreSQL, que es donde se puede
 * demostrar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenRevocadoServiceTest {

    @Mock
    private TokenRevocadoRepository tokenRevocadoRepository;

    private TokenRevocadoService servicio;

    @BeforeEach
    void prepararServicio() {
        servicio = new TokenRevocadoService(tokenRevocadoRepository);
    }

    @Test
    @DisplayName("Revocar guarda el jti con su caducidad, nunca el token entero")
    void revocarGuardaSoloElJti() {
        Instant caducidad = Instant.now().plus(1, ChronoUnit.HOURS);

        servicio.revocar("jti-1", caducidad);

        ArgumentCaptor<TokenRevocado> guardado = ArgumentCaptor.forClass(TokenRevocado.class);
        verify(tokenRevocadoRepository).save(guardado.capture());
        assertThat(guardado.getValue().getJti()).isEqualTo("jti-1");
        assertThat(guardado.getValue().getExpiraEn()).isEqualTo(caducidad);
    }

    @Test
    @DisplayName("Un token revocado consta como tal hasta su caducidad")
    void reconoceTokenRevocado() {
        when(tokenRevocadoRepository.existsByJtiAndExpiraEnAfter(anyString(), any()))
                .thenReturn(true);

        assertThat(servicio.estaRevocado("jti-1")).isTrue();
    }

    @Test
    @DisplayName("Un token que nunca se revocó no consta")
    void noReconoceTokenDesconocido() {
        when(tokenRevocadoRepository.existsByJtiAndExpiraEnAfter(anyString(), any()))
                .thenReturn(false);

        assertThat(servicio.estaRevocado("jti-desconocido")).isFalse();
    }

    @Test
    @DisplayName("La caducidad entra en la propia consulta: un token ya expirado no consta")
    void laCaducidadSeConsultaEnLaBaseDeDatos() {
        servicio.estaRevocado("jti-viejo");

        // Lo importante es que se pregunte por "revocado Y aún vigente" en una
        // sola consulta, y no que se traiga la fila para descartarla luego.
        ArgumentCaptor<Instant> momento = ArgumentCaptor.forClass(Instant.class);
        verify(tokenRevocadoRepository)
                .existsByJtiAndExpiraEnAfter(anyString(), momento.capture());
        assertThat(momento.getValue()).isCloseTo(Instant.now(), within(5_000));
    }

    @Test
    @DisplayName("Un jti nulo no rompe: un token sin identificador nunca está revocado")
    void toleraJtiNulo() {
        servicio.revocar(null, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(servicio.estaRevocado(null)).isFalse();
        // Y no se toca la base de datos por un token que no tiene identificador.
        verifyNoInteractions(tokenRevocadoRepository);
    }

    @Test
    @DisplayName("Revocar purga de paso lo ya caducado, para que la tabla no crezca sin fin")
    void revocarPurgaLoCaducado() {
        servicio.revocar("jti-2", Instant.now().plus(1, ChronoUnit.HOURS));

        verify(tokenRevocadoRepository).purgarCaducadosAntesDe(any(Instant.class));
    }

    @Test
    @DisplayName("Comprobar si un token está revocado no purga nada: es una lectura")
    void comprobarNoEscribe() {
        servicio.estaRevocado("jti-3");

        verify(tokenRevocadoRepository, never()).purgarCaducadosAntesDe(any());
        verify(tokenRevocadoRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------

    private static org.assertj.core.data.TemporalUnitOffset within(long milisegundos) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
                milisegundos, ChronoUnit.MILLIS);
    }
}
