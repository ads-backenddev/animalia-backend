package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.dto.request.ActualizarAlertaRequest;
import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.request.CrearMatchRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.MatchResponse;
import com.rescateanimal.backend.exception.AlertaAjenaException;
import com.rescateanimal.backend.exception.LimiteAvistamientosAlcanzadoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.service.AdminService;
import com.rescateanimal.backend.service.AlertaService;
import com.rescateanimal.backend.service.AlmacenFotosService;
import com.rescateanimal.backend.service.AvistamientoService;
import com.rescateanimal.backend.service.MatchService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * Las carreras sobre una misma alerta y sus avistamientos —el dueño, el
 * administrador, quien registra un avistamiento y el dueño que desliza uno—,
 * con transacciones de verdad en PostgreSQL.
 *
 * Una operación se queda a medias con su transacción abierta (retener), y la
 * otra se lanza en otro hilo. La prueba espera a ver que la segunda está
 * bloqueada POR LA PRIMERA —así se sabe que ya leyó, o lo intentó, antes de que
 * la primera confirme—, la deja confirmar y comprueba en qué estado queda todo.
 * Sin los bloqueos de AlertaRepository y AvistamientoRepository la segunda no
 * espera nunca, y la prueba falla por tiempo.
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
    /** El cupo diario de avistamientos por alerta de application.properties. */
    private static final int LIMITE_POR_ALERTA = 5;
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
    private MatchService matchService;
    @Autowired
    private AdminService adminService;
    @PersistenceContext
    private EntityManager entityManager;
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
    /** Un segundo testigo, con su propio cupo, para lo que no debe esperarse. */
    private Usuario otroTestigo;
    private String fotoDelOtroTestigo;
    private Long alertaId;
    private String especie;

    @BeforeEach
    void preparar() {
        especie = "bloqueo-" + UUID.randomUUID().toString().substring(0, 8);
        duenyo = usuario("Dueño");
        testigo = usuario("Testigo");
        otroTestigo = usuario("Otro testigo");
        fotoDelTestigo = fotoDe(testigo);
        fotoDelOtroTestigo = fotoDe(otroTestigo);

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
        // Lo que dejen los swipes, también si una prueba falla con el match ya
        // hecho. Por usuario, y son los tres de esta prueba.
        Object[] usuarios = {duenyo.getId(), testigo.getId(), otroTestigo.getId()};
        jdbc.update("DELETE FROM chat WHERE match_id IN "
                + "(SELECT id FROM match_registro WHERE usuario_id IN (?, ?, ?))", usuarios);
        jdbc.update("DELETE FROM match_registro WHERE usuario_id IN (?, ?, ?)", usuarios);
        jdbc.update("DELETE FROM notificacion WHERE usuario_id IN (?, ?, ?)", usuarios);
        jdbc.update("DELETE FROM limite_match_diario WHERE usuario_id IN (?, ?, ?)", usuarios);
        // Directo por alerta_id: la tabla es de la base de datos compartida, y
        // cargarla entera para filtrar en memoria crece con todo lo que haya.
        jdbc.update("DELETE FROM limite_avistamiento_diario WHERE alerta_id = ?", alertaId);
        jdbc.update("DELETE FROM avistamiento WHERE alerta_id = ?", alertaId);
        alertaRepository.deleteById(alertaId);
        fotoRepository.deleteById(almacenFotos.nombreDe(fotoDelTestigo).orElseThrow());
        fotoRepository.deleteById(almacenFotos.nombreDe(fotoDelOtroTestigo).orElseThrow());
        usuarioRepository.delete(otroTestigo);
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
    @DisplayName("Dos avistamientos de personas distintas sobre la misma alerta no se esperan: bloqueo compartido y un cupo cada una")
    void dosAvistamientosALaVez() throws Exception {
        Retenida primero = retener(this::avistar);

        // El primero sigue con su transacción abierta, y sus bloqueos, mientras
        // el segundo se registra y confirma entero.
        CompletableFuture.runAsync(() -> avistarComo(otroTestigo, fotoDelOtroTestigo), hilos)
                .get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        assertThat(primero.transaccion).as("el primero sigue sin confirmar").isNotDone();

        primero.confirmar();
        assertThat(avistamientos()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dos avistamientos de la misma persona a la vez se esperan en su fila del cupo, y entran los dos")
    void dosAvistamientosDelMismoTestigoSeEsperan() throws Exception {
        Retenida primero = retener(this::avistar);

        CompletableFuture<Void> segundo = CompletableFuture.runAsync(this::avistar, hilos);
        esperarAQueLaBloquee(primero);
        assertThat(segundo).as("el segundo espera al cupo del primero").isNotDone();

        primero.confirmar();

        segundo.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        assertThat(avistamientos()).isEqualTo(2);
    }

    @Test
    @DisplayName("Con un solo hueco en el cupo y dos avistamientos a la vez, entra uno y el otro recibe el 429")
    void elCupoNoSePasaConDosALaVez() throws Exception {
        for (int i = 0; i < LIMITE_POR_ALERTA - 1; i++) {
            avistar();
        }
        Retenida ultimoHueco = retener(this::avistar);

        CompletableFuture<Void> deMas = CompletableFuture.runAsync(this::avistar, hilos);
        esperarAQueLaBloquee(ultimoHueco);

        ultimoHueco.confirmar();

        // Al soltarse la fila, el UPDATE condicional del segundo vuelve a mirar
        // el contador, ya en el límite, y no suma.
        assertThatThrownBy(() -> deMas.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LimiteAvistamientosAlcanzadoException.class);
        assertThat(avistamientos()).isEqualTo(LIMITE_POR_ALERTA);
    }

    @Test
    @DisplayName("Un match del dueño durante el cierre espera, ve la ENCONTRADA y no abre chat")
    void matchDuranteElCierre() throws Exception {
        avistar();
        long avistamiento = idDelAvistamiento();
        Retenida cierre = retener(() -> alertaService.cerrar(alertaId, duenyo.getId()));

        CompletableFuture<MatchResponse> match = CompletableFuture.supplyAsync(() ->
                matchService.deslizar(swipeA(avistamiento), duenyo.getId()), hilos);
        esperarAQueLaBloquee(cierre);
        assertThat(match).as("el match espera al cierre").isNotDone();

        cierre.confirmar();

        assertThatThrownBy(() -> match.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(OperacionNoPermitidaException.class);
        assertThat(deslizamientos()).as("ni registro ni chat sobre una alerta cerrada").isZero();
    }

    @Test
    @DisplayName("Un match del dueño durante la moderación del avistamiento espera, ve el ELIMINADO y no abre chat")
    void matchDuranteLaModeracionDelAvistamiento() throws Exception {
        avistar();
        long avistamiento = idDelAvistamiento();
        // La moderación escribe el estado al hacer flush, justo antes del
        // commit; se fuerza aquí para retenerla con el UPDATE ya hecho y la
        // fila tomada, que es la ventana en la que el match no debe colarse.
        Retenida moderacion = retener(() -> {
            adminService.retirarPublicacion(TipoContenido.AVISTAMIENTO, avistamiento, ADMIN);
            entityManager.flush();
        });

        CompletableFuture<MatchResponse> match = CompletableFuture.supplyAsync(() ->
                matchService.deslizar(swipeA(avistamiento), duenyo.getId()), hilos);
        esperarAQueLaBloquee(moderacion);
        assertThat(match).as("el match espera a la moderación del avistamiento").isNotDone();

        moderacion.confirmar();

        assertThatThrownBy(() -> match.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(OperacionNoPermitidaException.class)
                .satisfies(e -> assertThat(((OperacionNoPermitidaException) e.getCause()).getCodigo())
                        .isEqualTo("AVISTAMIENTO_ELIMINADO"));
        assertThat(deslizamientos()).as("ni registro ni chat sobre contenido retirado").isZero();
    }

    @Test
    @DisplayName("Una moderación del avistamiento durante un match del dueño espera a que confirme, y se aplica después")
    void moderacionDelAvistamientoDuranteUnMatch() throws Exception {
        avistar();
        long avistamiento = idDelAvistamiento();
        Retenida match = retener(() -> matchService.deslizar(swipeA(avistamiento), duenyo.getId()));

        CompletableFuture<Void> moderacion = CompletableFuture.runAsync(() ->
                adminService.retirarPublicacion(TipoContenido.AVISTAMIENTO, avistamiento, ADMIN), hilos);
        esperarAQueLaBloquee(match);
        assertThat(moderacion).as("la moderación espera al match").isNotDone();

        match.confirmar();

        moderacion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT estado FROM avistamiento WHERE id = ?", String.class, avistamiento))
                .isEqualTo("ELIMINADO");
        assertThat(deslizamientos()).as("el match confirmó antes que la moderación").isEqualTo(1);
    }

    @Test
    @DisplayName("Quien no es el dueño recibe ALERTA_AJENA al momento: su swipe no espera ni bloquea")
    void unSwipeAjenoNoEspera() throws Exception {
        avistar();
        long avistamiento = idDelAvistamiento();
        Retenida moderacion = retener(() -> alertaService.retirarPorModeracion(alertaId, ADMIN));

        CompletableFuture<MatchResponse> ajeno = CompletableFuture.supplyAsync(() ->
                matchService.deslizar(swipeA(avistamiento), testigo.getId()), hilos);
        assertThatThrownBy(() -> ajeno.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AlertaAjenaException.class);
        assertThat(moderacion.transaccion).as("la moderación sigue abierta").isNotDone();

        moderacion.confirmar();
    }

    // ---------------------------------------------------------------------

    private static CrearMatchRequest swipeA(long avistamientoId) {
        return new CrearMatchRequest(avistamientoId, TipoPublicacion.AVISTAMIENTO, true);
    }

    private long idDelAvistamiento() {
        Long id = jdbc.queryForObject("SELECT id FROM avistamiento WHERE alerta_id = ?", Long.class, alertaId);
        return id == null ? 0 : id;
    }

    private int deslizamientos() {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM match_registro WHERE tipo_publicacion = "
                + "'AVISTAMIENTO' AND publicacion_id IN (SELECT id FROM avistamiento WHERE alerta_id = ?)",
                Integer.class, alertaId);
        return total == null ? 0 : total;
    }

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
        avistarComo(testigo, fotoDelTestigo);
    }

    private void avistarComo(Usuario quien, String foto) {
        avistamientoService.registrar(new CrearAvistamientoRequest(alertaId, "Visto en la esquina",
                foto, new UbicacionRequest(LAT, LNG)), quien.getId());
    }

    /** registrar exige que la foto sea de quien avista; para eso basta la fila. */
    private String fotoDe(Usuario usuario) {
        Foto foto = new Foto();
        foto.setNombre(UUID.randomUUID() + ".jpg");
        foto.setUsuario(usuario);
        foto.setTamanyo(1);
        fotoRepository.save(foto);
        return almacenFotos.urlDe(foto.getNombre());
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
