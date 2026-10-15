package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.dto.request.ActualizarAlertaRequest;
import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.service.AlertaService;
import com.rescateanimal.backend.service.AlmacenFotosService;
import com.rescateanimal.backend.service.AvistamientoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las carreras sobre una misma alerta —el dueño, el administrador y quien
 * registra un avistamiento—, con transacciones de verdad en PostgreSQL.
 *
 * Una operación se queda a medias con su transacción abierta (retener), y la
 * otra se lanza en otro hilo. La prueba espera a ver que la segunda está
 * bloqueada POR LA PRIMERA —así se sabe que ya leyó, o lo intentó, antes de que
 * la primera confirme—, la deja confirmar y comprueba en qué estado queda todo.
 * Sin los bloqueos de AlertaRepository la segunda no espera nunca, y la prueba
 * falla por tiempo.
 *
 * LA SONDA SOLO RECONOCE A ESTA PRUEBA. La base de datos es compartida: otra
 * prueba o la aplicación arrancada desde el IDE pueden tener sus propias
 * esperas. Por eso la operación retenida anota el pid de su conexión, y la
 * sonda busca una conexión cuyo bloqueante sea ese pid (pg_blocking_pids), no
 * cualquier espera sobre la tabla.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class AlertaBloqueoTest {

    private static final double LAT = 40.30;
    private static final double LNG = -3.90;
    /** Solo va al log de la moderación: no hace falta que exista. */
    private static final long ADMIN = 1L;
    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(10);

    private final GeometryFactory geometrias = new GeometryFactory(new PrecisionModel(), 4326);
    private final ExecutorService hilos = Executors.newFixedThreadPool(2);
    private final List<Retenida> retenidas = new ArrayList<>();

    @Autowired
    private AlertaService alertaService;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private AvistamientoService avistamientoService;
    @Autowired
    private AvistamientoRepository avistamientoRepository;
    @Autowired
    private FotoRepository fotoRepository;
    @Autowired
    private AlmacenFotosService almacenFotos;
    @Autowired
    private PlatformTransactionManager transacciones;
    @Autowired
    private JdbcTemplate jdbc;

    private Usuario duenyo;
    private Usuario testigo;
    private String fotoDelTestigo;
    private Long alertaId;
    private String especie;

    @BeforeEach
    void preparar() {
        especie = "bloqueo-" + UUID.randomUUID().toString().substring(0, 8);
        duenyo = usuario("Dueño");
        testigo = usuario("Testigo");

        // registrar exige que la foto sea del testigo; para eso basta la fila.
        Foto foto = new Foto();
        foto.setNombre(UUID.randomUUID() + ".jpg");
        foto.setUsuario(testigo);
        foto.setTamanyo(1);
        fotoRepository.save(foto);
        fotoDelTestigo = almacenFotos.urlDe(foto.getNombre());

        Alerta alerta = new Alerta();
        alerta.setUsuario(duenyo);
        alerta.setDescripcion("Para la carrera");
        alerta.setEspecie(especie);
        alerta.setFechaAvistamiento(Instant.now());
        alerta.setUltimaUbicacion(geometrias.createPoint(new Coordinate(LNG, LAT)));
        alerta.setEstado(EstadoAlerta.ACTIVA);
        alertaId = alertaRepository.save(alerta).getId();
    }

    /**
     * Si una prueba falla a medias, su operación retenida sigue con la fila
     * bloqueada: se la suelta y se espera a que termine antes de borrar nada,
     * para que el borrado no se quede esperando ese bloqueo.
     */
    @AfterEach
    void limpiar() {
        for (Retenida retenida : retenidas) {
            retenida.confirmar.countDown();
            try {
                retenida.transaccion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ya ha fallado la prueba, o la operación retenida falló a propósito.
            }
        }
        hilos.shutdownNow();
        avistamientoRepository.deleteAll(avistamientoRepository.findAll().stream()
                .filter(a -> a.getAlerta().getId().equals(alertaId)).toList());
        alertaRepository.deleteById(alertaId);
        fotoRepository.deleteById(almacenFotos.nombreDe(fotoDelTestigo).orElseThrow());
        usuarioRepository.delete(testigo);
        usuarioRepository.delete(duenyo);
    }

    @Test
    @DisplayName("El dueño edita mientras se modera: espera, ve la ELIMINADA y no la resucita")
    void edicionDuranteLaModeracion() throws Exception {
        Retenida moderacion = retener(() -> alertaService.retirarPorModeracion(alertaId, ADMIN));

        CompletableFuture<Void> edicion = CompletableFuture.runAsync(() ->
                alertaService.actualizar(alertaId, peticionDeEdicion(), duenyo.getId()), hilos);
        esperarAQueLaBloquee(moderacion);
        assertThat(edicion).as("la edición espera a la moderación").isNotDone();

        moderacion.confirmar();

        assertThatThrownBy(() -> edicion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(OperacionNoPermitidaException.class);
        assertThat(estado()).isEqualTo(EstadoAlerta.ELIMINADA);
    }

    @Test
    @DisplayName("Se modera mientras el dueño retira: la moderación espera y queda encima")
    void moderacionDuranteLaRetirada() throws Exception {
        Retenida retirada = retener(() -> alertaService.retirar(alertaId, duenyo.getId()));

        CompletableFuture<Boolean> moderacion = CompletableFuture.supplyAsync(() ->
                alertaService.retirarPorModeracion(alertaId, ADMIN), hilos);
        esperarAQueLaBloquee(retirada);
        assertThat(moderacion).as("la moderación espera a la retirada").isNotDone();

        retirada.confirmar();

        assertThat(moderacion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(estado()).isEqualTo(EstadoAlerta.ELIMINADA);
    }

    @Test
    @DisplayName("Quien no es el dueño recibe el 403 al momento: no espera ni bloquea la alerta")
    void unTerceroNoEsperaNiBloquea() throws Exception {
        Retenida moderacion = retener(() -> alertaService.retirarPorModeracion(alertaId, ADMIN));

        // La moderación sigue sin confirmar, con la fila bloqueada, y aun así el
        // tercero tiene su respuesta sin esperarla.
        CompletableFuture<Void> tercero = CompletableFuture.runAsync(() ->
                alertaService.actualizar(alertaId, peticionDeEdicion(), testigo.getId()), hilos);
        assertThatThrownBy(() -> tercero.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(moderacion.transaccion).as("la moderación sigue abierta").isNotDone();

        moderacion.confirmar();
    }

    @Test
    @DisplayName("Un avistamiento durante la moderación espera, ve la ELIMINADA y no se registra")
    void avistamientoDuranteLaModeracion() throws Exception {
        Retenida moderacion = retener(() -> alertaService.retirarPorModeracion(alertaId, ADMIN));

        CompletableFuture<Void> avistamiento = CompletableFuture.runAsync(this::avistar, hilos);
        esperarAQueLaBloquee(moderacion);
        assertThat(avistamiento).as("el avistamiento espera a la moderación").isNotDone();

        moderacion.confirmar();

        assertThatThrownBy(() -> avistamiento.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(OperacionNoPermitidaException.class);
        assertThat(avistamientos()).isZero();
    }

    @Test
    @DisplayName("Una moderación durante un avistamiento espera a que se registre, y se aplica después")
    void moderacionDuranteUnAvistamiento() throws Exception {
        Retenida avistamiento = retener(this::avistar);

        CompletableFuture<Boolean> moderacion = CompletableFuture.supplyAsync(() ->
                alertaService.retirarPorModeracion(alertaId, ADMIN), hilos);
        esperarAQueLaBloquee(avistamiento);
        assertThat(moderacion).as("la moderación espera al avistamiento").isNotDone();

        avistamiento.confirmar();

        assertThat(moderacion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(estado()).isEqualTo(EstadoAlerta.ELIMINADA);
        assertThat(avistamientos()).as("se registró mientras estaba ACTIVA").isEqualTo(1);
    }

    @Test
    @DisplayName("Dos avistamientos sobre la misma alerta no se esperan: el bloqueo es compartido")
    void dosAvistamientosALaVez() throws Exception {
        Retenida primero = retener(this::avistar);

        // El primero sigue con su transacción abierta, y su bloqueo, mientras
        // el segundo se registra y confirma entero.
        CompletableFuture.runAsync(this::avistar, hilos).get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        assertThat(primero.transaccion).as("el primero sigue sin confirmar").isNotDone();

        primero.confirmar();
        assertThat(avistamientos()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------

    /** Una operación que ya tiene la fila y no confirma hasta que se le dice. */
    private static final class Retenida {
        private final CountDownLatch dentro = new CountDownLatch(1);
        private final CountDownLatch confirmar = new CountDownLatch(1);
        private CompletableFuture<Void> transaccion;
        /** El pid de PostgreSQL de su conexión, para que la sonda la reconozca. */
        private volatile int pid;

        /** La deja confirmar y espera a que lo haya hecho. */
        void confirmar() throws Exception {
            confirmar.countDown();
            transaccion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /**
     * Ejecuta la operación dentro de una transacción que se queda abierta, y
     * vuelve cuando la operación ya ha terminado y tiene la fila.
     *
     * El pid se lee con el JdbcTemplate dentro de la misma transacción: el
     * gestor de transacciones de JPA le pasa su propia conexión, así que es el
     * pid de la conexión que tiene el bloqueo.
     */
    private Retenida retener(Runnable operacion) throws InterruptedException {
        Retenida retenida = new Retenida();
        retenidas.add(retenida);
        retenida.transaccion = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                    operacion.run();
                    retenida.pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    retenida.dentro.countDown();
                    esperar(retenida.confirmar);
                }), hilos);
        assertThat(retenida.dentro.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .as("la operación retenida llegó a tener la fila").isTrue();
        return retenida;
    }

    /** Espera a que alguna conexión esté bloqueada precisamente por la retenida. */
    private void esperarAQueLaBloquee(Retenida retenida) throws InterruptedException {
        Instant limite = Instant.now().plus(ESPERA_MAXIMA);
        while (Instant.now().isBefore(limite)) {
            Integer bloqueadas = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Integer.class, retenida.pid);
            if (bloqueadas != null && bloqueadas > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("La segunda operación no llegó a esperar a la primera: no hay bloqueo");
    }

    private static void esperar(CountDownLatch latch) {
        try {
            if (!latch.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("La prueba no dio paso a confirmar");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private ActualizarAlertaRequest peticionDeEdicion() {
        return new ActualizarAlertaRequest("Descripción nueva", null, especie, Instant.now(),
                new UbicacionRequest(LAT, LNG), null);
    }

    private void avistar() {
        avistamientoService.registrar(new CrearAvistamientoRequest(alertaId, "Visto en la esquina",
                fotoDelTestigo, new UbicacionRequest(LAT, LNG)), testigo.getId());
    }

    private int avistamientos() {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM avistamiento WHERE alerta_id = ?",
                Integer.class, alertaId);
        return total == null ? 0 : total;
    }

    private Usuario usuario(String nombre) {
        Usuario nuevo = new Usuario();
        nuevo.setNombre(nombre);
        nuevo.setEmail("bloqueo-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        return usuarioRepository.save(nuevo);
    }

    private EstadoAlerta estado() {
        return EstadoAlerta.valueOf(
                jdbc.queryForObject("SELECT estado FROM alerta WHERE id = ?", String.class, alertaId));
    }
}
