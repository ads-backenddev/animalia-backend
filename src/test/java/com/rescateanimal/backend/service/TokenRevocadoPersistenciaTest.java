package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.TokenRevocadoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La revocación de tokens contra PostgreSQL de verdad (RF-16, RF-17).
 *
 * ESTA ES LA PRUEBA QUE JUSTIFICA LA MIGRACIÓN V8. Hasta ahora las revocaciones
 * vivían en un ConcurrentHashMap dentro del service, así que se perdían al
 * reiniciar la aplicación: un token del que alguien había hecho logout volvía a
 * autenticar durante lo que le quedara de sus 24 horas, y reiniciar no es un caso
 * raro sino cada despliegue.
 *
 * EL REINICIO SE SIMULA CON DOS INSTANCIAS DEL SERVICE sobre la misma base de
 * datos: quien revoca nunca es quien pregunta después. Es lo que ocurre al
 * arrancar de nuevo —el proceso anterior se lleva su memoria y el siguiente
 * empieza con la suya vacía—, y sirve además de red contra la regresión de volver
 * a meter una caché dentro del service.
 *
 * QUIEN ESCRIBE ES SIEMPRE EL BEAN INYECTADO, no una instancia construida a mano,
 * y esto no es un detalle de estilo: `revocar` está anotado @Transactional y esa
 * anotación la aplica el proxy de Spring, no la clase. Con un `new
 * TokenRevocadoService(...)` no hay proxy, así que no hay transacción, y el DELETE
 * de la purga muere con "No active transaction for update or delete query". La
 * instancia construida a mano se usa solo para LEER, que no necesita transacción y
 * es justo el lado donde importa demostrar que no queda estado en memoria.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d"),
 * como el resto de pruebas de integración.
 */
@ActiveProfiles("test")
@SpringBootTest
class TokenRevocadoPersistenciaTest {

    @Autowired
    private TokenRevocadoRepository tokenRevocadoRepository;

    /** El bean de verdad, con su proxy transaccional: es el que revoca. */
    @Autowired
    private TokenRevocadoService servicio;

    /** Los jti creados por esta prueba, para no dejar rastro en la base compartida. */
    private final List<String> creados = new ArrayList<>();

    @AfterEach
    void limpiar() {
        tokenRevocadoRepository.deleteAllById(creados);
    }

    @Test
    @DisplayName("El logout sigue revocando tras un reinicio de la aplicación")
    void laRevocacionSobreviveAlReinicio() {
        String jti = nuevoJti();

        servicio.revocar(jti, Instant.now().plus(24, ChronoUnit.HOURS));
        assertThat(servicio.estaRevocado(jti)).isTrue();

        // Instancia "después del reinicio": memoria vacía, misma base de datos.
        TokenRevocadoService despues = new TokenRevocadoService(tokenRevocadoRepository);

        assertThat(despues.estaRevocado(jti))
                .as("un token deslogueado no puede resucitar porque se reinicie el servidor")
                .isTrue();
    }

    @Test
    @DisplayName("Lo que nunca se revocó tampoco consta tras el reinicio")
    void loNoRevocadoSigueSinConstar() {
        TokenRevocadoService despues = new TokenRevocadoService(tokenRevocadoRepository);

        assertThat(despues.estaRevocado(nuevoJti())).isFalse();
    }

    @Test
    @DisplayName("Un token revocado pero ya caducado deja de constar: su firma ya lo rechaza")
    void elCaducadoDejaDeConstar() {
        String jti = nuevoJti();

        servicio.revocar(jti, Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(servicio.estaRevocado(jti)).isFalse();
    }

    @Test
    @DisplayName("Revocar dos veces el mismo token deja una sola fila")
    void revocarDosVecesEsIdempotente() {
        String jti = nuevoJti();
        Instant caducidad = Instant.now().plus(1, ChronoUnit.HOURS);

        servicio.revocar(jti, caducidad);
        servicio.revocar(jti, caducidad);

        assertThat(tokenRevocadoRepository.findById(jti)).isPresent();
        assertThat(servicio.estaRevocado(jti)).isTrue();
    }

    @Test
    @DisplayName("La purga borra lo caducado y respeta lo vigente")
    void laPurgaSoloBorraLoCaducado() {
        String caducado = nuevoJti();
        String vigente = nuevoJti();

        servicio.revocar(vigente, Instant.now().plus(1, ChronoUnit.HOURS));
        servicio.revocar(caducado, Instant.now().minus(1, ChronoUnit.HOURS));

        // El segundo revocar ya purga; el caducado no debería haber sobrevivido.
        assertThat(tokenRevocadoRepository.findById(caducado)).isEmpty();
        assertThat(tokenRevocadoRepository.findById(vigente)).isPresent();
    }

    // ---------------------------------------------------------------------

    private String nuevoJti() {
        String jti = UUID.randomUUID().toString();
        creados.add(jti);
        return jti;
    }
}
