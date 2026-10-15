package com.rescateanimal.backend.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.FotosDePrueba;
import com.rescateanimal.backend.exception.GlobalExceptionHandler;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.LimiteFotoDiarioRepository;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.service.AlertaService;
import com.rescateanimal.backend.service.AlmacenFotosService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La espera máxima de los bloqueos de alerta, contra PostgreSQL de verdad y a
 * través de HTTP: lo que importa es que el cliente reciba un error controlado,
 * no un hilo colgado ni un 500.
 *
 * Una moderación se queda a medias con la fila bloqueada, y mientras tanto el
 * dueño intenta editar la alerta y un testigo registrar un avistamiento. Los
 * dos tienen que fallar en cuanto pasa la espera máxima —aquí 1 s, para que la
 * prueba no tarde— con 409 RECURSO_OCUPADO, y sin haber cambiado nada.
 *
 * Se acorta la espera con la variable JPA_LOCK_TIMEOUT_MS y no sobrescribiendo
 * la propiedad de JPA: así la prueba pasa por la línea de application.properties
 * que la lee, y falla si esa línea desaparece.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "JPA_LOCK_TIMEOUT_MS=1000")
class AlertaEsperaBloqueoTest {

    private static final Duration ESPERA_DE_BLOQUEO = Duration.ofSeconds(1);
    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(10);
    private static final String CLAVE = "passw0rd123";
    private static final long ADMIN = 1L;

    @LocalServerPort
    private int puerto;

    @Autowired
    private AlertaService alertaService;
    @Autowired
    private PlatformTransactionManager transacciones;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private AvistamientoRepository avistamientoRepository;
    @Autowired
    private NotificacionRepository notificacionRepository;
    @Autowired
    private FotoRepository fotoRepository;
    @Autowired
    private LimiteFotoDiarioRepository limiteFotoDiarioRepository;
    @Autowired
    private AlmacenFotosService almacenFotos;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService hilos = Executors.newSingleThreadExecutor();
    private final List<Long> usuarios = new ArrayList<>();
    private final CountDownLatch confirmar = new CountDownLatch(1);
    private final Logger logDelManejador = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> registro = new ListAppender<>();

    private String tokenDuenyo;
    private String tokenTestigo;
    private long alerta;
    private double latitud;
    private double longitud;
    private String especie;

    @BeforeEach
    void preparar() throws Exception {
        registro.start();
        logDelManejador.addAppender(registro);
        especie = "espera-" + UUID.randomUUID().toString().substring(0, 8);
        latitud = 41.10 + Math.random() / 10;
        longitud = -3.40 - Math.random() / 10;
        tokenDuenyo = entrar(registrar("duenyo"));
        tokenTestigo = entrar(registrar("testigo"));

        HttpResponse<String> alta = enviar("POST", "/api/alertas", tokenDuenyo, cuerpo("Perro negro"));
        assertThat(alta.statusCode()).as(alta.body()).isEqualTo(201);
        alerta = ((Number) JsonPath.read(alta.body(), "$.id")).longValue();
    }

    @AfterEach
    void limpiar() {
        logDelManejador.detachAppender(registro);
        confirmar.countDown();
        hilos.shutdown();
        avistamientoRepository.deleteAll(avistamientoRepository.findAll().stream()
                .filter(a -> usuarios.contains(a.getUsuario().getId())).toList());
        alertaRepository.deleteAll(alertaRepository.findAll().stream()
                .filter(a -> usuarios.contains(a.getUsuario().getId())).toList());
        notificacionRepository.deleteAll(notificacionRepository.findAll().stream()
                .filter(n -> usuarios.contains(n.getUsuario().getId())).toList());
        FotosDePrueba.borrarLasDe(usuarios, fotoRepository, limiteFotoDiarioRepository, almacenFotos);
        usuarioRepository.deleteAllById(usuarios);
    }

    @Test
    @DisplayName("Editar una alerta bloqueada falla con 409 RECURSO_OCUPADO al pasar la espera máxima")
    void edicionConLaFilaBloqueada() throws Exception {
        CompletableFuture<Void> moderacion = moderarSinConfirmar();

        Instant inicio = Instant.now();
        HttpResponse<String> edicion = enviar("PUT", "/api/alertas/" + alerta, tokenDuenyo, cuerpo("Editada"));
        Duration tardo = Duration.between(inicio, Instant.now());

        assertThat(edicion.statusCode()).as(edicion.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(edicion.body(), "$.codigo")).isEqualTo("RECURSO_OCUPADO");
        assertThat(tardo).as("esperó el bloqueo y se rindió a su tiempo")
                .isGreaterThanOrEqualTo(ESPERA_DE_BLOQUEO.minusMillis(100))
                .isLessThan(ESPERA_MAXIMA.dividedBy(2));
        assertThat(moderacion).as("la moderación sigue abierta").isNotDone();
        // Con la excepción de verdad, la que llega de PostgreSQL: el manejador saca
        // de ella el SQLState 55P03 y lo registra como espera vencida, no como error.
        assertThat(registro.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
        assertThat(registro.list.get(0).getFormattedMessage()).contains("Bloqueo no conseguido a tiempo");

        confirmar.countDown();
        moderacion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT descripcion FROM alerta WHERE id = ?", String.class, alerta))
                .as("la edición no llegó a aplicarse").isEqualTo("Perro negro");
    }

    @Test
    @DisplayName("Registrar un avistamiento sobre una alerta bloqueada también falla con 409 a su tiempo")
    void avistamientoConLaFilaBloqueada() throws Exception {
        String foto = FotosDePrueba.subir(http, puerto, tokenTestigo);
        CompletableFuture<Void> moderacion = moderarSinConfirmar();

        Instant inicio = Instant.now();
        HttpResponse<String> avistamiento = enviar("POST", "/api/avistamientos", tokenTestigo, """
                {"alertaId":%d,"descripcion":"Visto en el parque","fotoUrl":"%s",
                 "ubicacion":{"latitud":%s,"longitud":%s}}
                """.formatted(alerta, foto, latitud, longitud));
        Duration tardo = Duration.between(inicio, Instant.now());

        assertThat(avistamiento.statusCode()).as(avistamiento.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(avistamiento.body(), "$.codigo")).isEqualTo("RECURSO_OCUPADO");
        assertThat(tardo).isGreaterThanOrEqualTo(ESPERA_DE_BLOQUEO.minusMillis(100))
                .isLessThan(ESPERA_MAXIMA.dividedBy(2));

        confirmar.countDown();
        moderacion.get(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM avistamiento WHERE alerta_id = ?",
                Integer.class, alerta)).isZero();
    }

    // ---------------------------------------------------------------------

    /** Una moderación con la fila ya bloqueada, que no confirma hasta "confirmar". */
    private CompletableFuture<Void> moderarSinConfirmar() throws InterruptedException {
        CountDownLatch moderada = new CountDownLatch(1);
        CompletableFuture<Void> moderacion = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
                    alertaService.retirarPorModeracion(alerta, ADMIN);
                    moderada.countDown();
                    try {
                        confirmar.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }), hilos);
        assertThat(moderada.await(ESPERA_MAXIMA.toSeconds(), TimeUnit.SECONDS)).isTrue();
        return moderacion;
    }

    private String cuerpo(String descripcion) {
        return """
                {"descripcion":"%s","especie":"%s","fechaAvistamiento":"%s",
                 "ubicacion":{"latitud":%s,"longitud":%s},"radioBusqueda":1000}
                """.formatted(descripcion, especie, Instant.now().minus(1, ChronoUnit.HOURS), latitud, longitud);
    }

    private String registrar(String papel) throws Exception {
        String email = papel + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.es";
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"%s","email":"%s","password":"%s","areaGeografica":"Espera %s"}
                """.formatted(papel, email, CLAVE, email));
        assertThat(registro.statusCode()).isEqualTo(201);
        usuarios.add(((Number) JsonPath.read(registro.body(), "$.id")).longValue());
        return email;
    }

    private String entrar(String email) throws Exception {
        HttpResponse<String> login = enviar("POST", "/api/auth/login", null, """
                {"email":"%s","password":"%s"}
                """.formatted(email, CLAVE));
        assertThat(login.statusCode()).isEqualTo(200);
        return JsonPath.read(login.body(), "$.token");
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String token, String cuerpo) throws Exception {
        HttpRequest.Builder peticion = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + puerto + ruta))
                .timeout(ESPERA_MAXIMA)
                .header("Content-Type", "application/json")
                .method(metodo, cuerpo == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8));
        if (token != null) {
            peticion.header("Authorization", "Bearer " + token);
        }
        return http.send(peticion.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
