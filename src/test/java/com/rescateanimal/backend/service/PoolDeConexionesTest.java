package com.rescateanimal.backend.service;

import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Por qué Tomcat va por debajo del pool (ComprobacionPoolConexiones): un aviso
 * tras el commit pide una segunda conexión mientras su petición conserva la
 * primera (NotificacionService.trasConfirmar).
 *
 * Con un pool pequeño a propósito, cada hilo abre su transacción, programa un
 * aviso y, ya en el afterCommit —con su primera conexión todavía tomada y la
 * segunda sin pedir—, espera a que lleguen los demás. Es el peor caso: todos a
 * la vez en el único punto que necesita dos conexiones. Con un hilo menos que
 * conexiones se crean todos los avisos; con tantos hilos como conexiones el
 * pool se agota: ninguno avanza hasta que alguno agota su connectionTimeout, y
 * ese aviso se pierde.
 *
 * Contra PostgreSQL de verdad: lo que se mide es el pool real de Hikari y la
 * conexión que la transacción conserva durante sus afterCommit.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + PoolDeConexionesTest.CONEXIONES,
        "spring.datasource.hikari.minimum-idle=" + PoolDeConexionesTest.CONEXIONES,
        "spring.datasource.hikari.connection-timeout=" + PoolDeConexionesTest.ESPERA_DE_CONEXION_MS,
        // Lo exige ComprobacionPoolConexiones: este contexto no tiene Tomcat,
        // pero la regla se comprueba igual.
        "server.tomcat.threads.max=" + (PoolDeConexionesTest.CONEXIONES - 1)
})
class PoolDeConexionesTest {

    static final int CONEXIONES = 4;

    /**
     * El connectionTimeout de este contexto. Holgado para que el caso que sí
     * cabe no lo roce nunca, aunque la máquina vaya cargada.
     */
    static final long ESPERA_DE_CONEXION_MS = 2000;

    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(20);

    @Autowired
    private NotificacionService notificacionService;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private PlatformTransactionManager transacciones;
    @Autowired
    private JdbcTemplate jdbc;

    private Usuario dueno;
    private ExecutorService hilos;

    @BeforeEach
    void preparar() {
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Dueño");
        nuevo.setEmail("pool-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        dueno = usuarioRepository.save(nuevo);
    }

    @AfterEach
    void limpiar() {
        if (hilos != null) {
            hilos.shutdownNow();
        }
        jdbc.update("DELETE FROM notificacion WHERE usuario_id = ?", dueno.getId());
        usuarioRepository.delete(dueno);
    }

    @Test
    @DisplayName("Con un hilo menos que conexiones, todos a la vez en el afterCommit: no se pierde ningún aviso")
    void conUnHiloMenosQueConexionesNoSePierdeNinguno() throws Exception {
        Resultado resultado = avisosConHilosALaVez(CONEXIONES - 1);

        assertThat(resultado.creados()).isEqualTo(CONEXIONES - 1);
    }

    @Test
    @DisplayName("Con tantos hilos como conexiones el pool se agota, esperan el connectionTimeout y se pierden avisos")
    void conTantosHilosComoConexionesSePierdenAvisos() throws Exception {
        Resultado resultado = avisosConHilosALaVez(CONEXIONES);

        // Al menos el primero que agota su espera pierde el suyo; los demás
        // pueden aprovechar la conexión que suelta, o agotarla también.
        assertThat(resultado.creados()).isLessThan(CONEXIONES);
        assertThat(resultado.transcurrido()).isGreaterThanOrEqualTo(Duration.ofMillis(ESPERA_DE_CONEXION_MS));
    }

    private record Resultado(int creados, Duration transcurrido) {
    }

    /**
     * Lanza n transacciones a la vez. Cada una toma su conexión, programa un
     * NUEVO_AVISTAMIENTO para el dueño y, al confirmar, se para en un
     * afterCommit propio, registrado ANTES que el del aviso —se ejecutan en el
     * orden en que se registran—, hasta que llegan las demás. Devuelve cuántos
     * avisos llegaron a crearse y cuánto tardaron todas.
     */
    private Resultado avisosConHilosALaVez(int n) throws Exception {
        long inicio = System.nanoTime();
        CyclicBarrier todasConfirmadas = new CyclicBarrier(n);
        hilos = Executors.newFixedThreadPool(n);
        List<Future<?>> transaccionesEnCurso = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            // referencia_id no es una clave ajena: la alerta no necesita existir.
            long alertaId = 900_000_000L + i;
            transaccionesEnCurso.add(hilos.submit(() ->
                    new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                        // La primera conexión, que la transacción conserva hasta
                        // después de sus afterCommit.
                        usuarioRepository.findById(dueno.getId());
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                esperarALasDemas(todasConfirmadas);
                            }
                        });
                        // Autor distinto del dueño, o no habría aviso.
                        notificacionService.avisarNuevoAvistamiento(alertaId, dueno.getId(), -1L);
                    })));
        }
        for (Future<?> transaccion : transaccionesEnCurso) {
            transaccion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        }
        Duration transcurrido = Duration.ofNanos(System.nanoTime() - inicio);

        Integer creados = jdbc.queryForObject(
                "SELECT count(*) FROM notificacion WHERE usuario_id = ?", Integer.class, dueno.getId());
        return new Resultado(creados == null ? 0 : creados, transcurrido);
    }

    private static void esperarALasDemas(CyclicBarrier barrera) {
        try {
            barrera.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("Las transacciones no llegaron juntas al afterCommit", e);
        }
    }
}
