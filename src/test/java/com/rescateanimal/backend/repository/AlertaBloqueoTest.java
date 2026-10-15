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
 * La carrera entre el dueño y el administrador sobre la misma alerta, con dos
 * transacciones de verdad en PostgreSQL.
 *
 * Una de las dos operaciones se queda a medias con la transacción abierta, y la
 * otra se lanza en otro hilo. La prueba espera a ver en pg_stat_activity que la
 * segunda está bloqueada esperando la fila —así se sabe que ya leyó, o lo
 * intentó, antes del commit de la primera—, deja confirmar a la primera y
 * comprueba en qué estado queda la alerta. Sin el bloqueo de
 * AlertaRepository.bloquearParaModificar la segunda no espera nunca, y la
 * prueba falla por tiempo.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class AlertaBloqueoTest {

    private static final double LAT = 40.30;
    private static final double LNG = -3.90;
    private static final long ADMIN = 1L;
    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(10);

    private final GeometryFactory geometrias = new GeometryFactory(new PrecisionModel(), 4326);
    private final ExecutorService hilos = Executors.newFixedThreadPool(2);

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

    @AfterEach
    void limpiar() {
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
        CountDownLatch moderada = new CountDownLatch(1);
        CountDownLatch confirmar = new CountDownLatch(1);

        CompletableFuture<Void> admin = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                    alertaService.retirarPorModeracion(alertaId, ADMIN);
                    moderada.countDown();
                    esperar(confirmar);
                }), hilos);
        assertThat(moderada.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> edicion = CompletableFuture.runAsync(() ->
                alertaService.actualizar(alertaId, peticionDeEdicion(), duenyo.getId()), hilos);
        esperarAQueSeBloquee();
        assertThat(edicion).as("la edición espera a la moderación").isNotDone();

        confirmar.countDown();
        admin.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);

        assertThatThrownBy(() -> edicion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(OperacionNoPermitidaException.class);
        assertThat(estado()).isEqualTo(EstadoAlerta.ELIMINADA);
    }

    @Test
    @DisplayName("Se modera mientras el dueño retira: la moderación espera y queda encima")
    void moderacionDuranteLaRetirada() throws Exception {
        CountDownLatch retirada = new CountDownLatch(1);
        CountDownLatch confirmar = new CountDownLatch(1);

        CompletableFuture<Void> duenyoRetira = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                    alertaService.retirar(alertaId, duenyo.getId());
                    retirada.countDown();
                    esperar(confirmar);
                }), hilos);
        assertThat(retirada.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> moderacion = CompletableFuture.supplyAsync(() ->
                alertaService.retirarPorModeracion(alertaId, ADMIN), hilos);
        esperarAQueSeBloquee();
        assertThat(moderacion).as("la moderación espera a la retirada").isNotDone();

        confirmar.countDown();
        duenyoRetira.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);

        assertThat(moderacion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(estado()).isEqualTo(EstadoAlerta.ELIMINADA);
    }

    @Test
    @DisplayName("Quien no es el dueño recibe el 403 al momento: no espera ni bloquea la alerta")
    void unTerceroNoEsperaNiBloquea() throws Exception {
        CountDownLatch moderada = new CountDownLatch(1);
        CountDownLatch confirmar = new CountDownLatch(1);

        CompletableFuture<Void> admin = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                    alertaService.retirarPorModeracion(alertaId, ADMIN);
                    moderada.countDown();
                    esperar(confirmar);
                }), hilos);
        assertThat(moderada.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();

        // La moderación sigue sin confirmar, con la fila bloqueada, y aun así el
        // tercero tiene su respuesta sin esperarla.
        CompletableFuture<Void> tercero = CompletableFuture.runAsync(() ->
                alertaService.actualizar(alertaId, peticionDeEdicion(), testigo.getId()), hilos);
        assertThatThrownBy(() -> tercero.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(admin).as("la moderación sigue abierta").isNotDone();

        confirmar.countDown();
        admin.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Un avistamiento durante la moderación espera, ve la ELIMINADA y no se registra")
    void avistamientoDuranteLaModeracion() throws Exception {
        CountDownLatch moderada = new CountDownLatch(1);
        CountDownLatch confirmar = new CountDownLatch(1);

        CompletableFuture<Void> admin = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                    alertaService.retirarPorModeracion(alertaId, ADMIN);
                    moderada.countDown();
                    esperar(confirmar);
                }), hilos);
        assertThat(moderada.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> avistamiento = CompletableFuture.runAsync(this::avistar, hilos);
        esperarAQueSeBloquee();
        assertThat(avistamiento).as("el avistamiento espera a la moderación").isNotDone();

        confirmar.countDown();
        admin.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);

        assertThatThrownBy(() -> avistamiento.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(OperacionNoPermitidaException.class);
        assertThat(avistamientos()).isZero();
    }

    @Test
    @DisplayName("Una moderación durante un avistamiento espera a que se registre, y se aplica después")
    void moderacionDuranteUnAvistamiento() throws Exception {
        CountDownLatch registrado = new CountDownLatch(1);
        CountDownLatch confirmar = new CountDownLatch(1);

        CompletableFuture<Void> avistamiento = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                    avistar();
                    registrado.countDown();
                    esperar(confirmar);
                }), hilos);
        assertThat(registrado.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> moderacion = CompletableFuture.supplyAsync(() ->
                alertaService.retirarPorModeracion(alertaId, ADMIN), hilos);
        esperarAQueSeBloquee();
        assertThat(moderacion).as("la moderación espera al avistamiento").isNotDone();

        confirmar.countDown();
        avistamiento.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);

        assertThat(moderacion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(estado()).isEqualTo(EstadoAlerta.ELIMINADA);
        assertThat(avistamientos()).as("se registró mientras estaba ACTIVA").isEqualTo(1);
    }

    @Test
    @DisplayName("Dos avistamientos sobre la misma alerta no se esperan: el bloqueo es compartido")
    void dosAvistamientosALaVez() throws Exception {
        CountDownLatch registrado = new CountDownLatch(1);
        CountDownLatch confirmar = new CountDownLatch(1);

        CompletableFuture<Void> primero = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                    avistar();
                    registrado.countDown();
                    esperar(confirmar);
                }), hilos);
        assertThat(registrado.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();

        // El primero sigue con su transacción abierta, y su bloqueo, mientras
        // el segundo se registra y confirma entero.
        CompletableFuture.runAsync(this::avistar, hilos).get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        assertThat(primero).as("el primero sigue sin confirmar").isNotDone();

        confirmar.countDown();
        primero.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        assertThat(avistamientos()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------

    /**
     * Espera a que alguna conexión esté parada esperando un bloqueo sobre la
     * tabla alerta. Se mira la consulta para no confundirla con otra espera
     * ajena a esta prueba en la base de datos compartida.
     */
    private void esperarAQueSeBloquee() throws InterruptedException {
        Instant limite = Instant.now().plus(ESPERA_MAXIMA);
        while (Instant.now().isBefore(limite)) {
            Integer esperando = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%from alerta%'
                    """, Integer.class);
            if (esperando != null && esperando > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("La segunda operación no llegó a esperar por la fila: no hay bloqueo");
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
