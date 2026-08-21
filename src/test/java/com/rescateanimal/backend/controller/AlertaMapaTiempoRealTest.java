package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.service.AlertaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Prueba de extremo a extremo del bloque de alertas y mapa en tiempo real
 * (RF-03, RF-04, RF-14, RF-25).
 *
 * Es la demostración del recorrido completo con las piezas reales —servidor
 * arrancado, PostGIS, WebSocket—, no con dobles: un ciudadano se registra,
 * abre el mapa, publica una alerta y ve llegar el evento por /topic/mapa;
 * después intenta publicar la misma alerta y recibe 409.
 *
 * REQUISITOS PARA EJECUTARLA: la base de datos PostGIS levantada
 * ("docker compose up -d") y el perfil "local" activo, igual que
 * BackendApplicationTests. La detección de duplicados se apoya en ST_DWithin,
 * que solo existe en PostGIS: con una base en memoria no probaría nada.
 *
 * La especie se genera aleatoria en cada ejecución porque buscarSimilar filtra
 * por ella: así las alertas que hayan quedado en la base de datos de desarrollo
 * no pueden interferir con la prueba.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890")
class AlertaMapaTiempoRealTest {

    /** Puerta del Sol, Madrid. */
    private static final double LATITUD = 40.416775;
    private static final double LONGITUD = -3.703790;

    /**
     * Unos 300 m al norte del punto anterior: dentro del radio de 500 m de
     * RF-25, así que la segunda alerta debe rechazarse. Un grado de latitud son
     * unos 111 320 m.
     */
    private static final double LATITUD_CERCANA = LATITUD + 0.0027;

    private static final Duration ESPERA = Duration.ofSeconds(10);

    @LocalServerPort
    private int puerto;

    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    private String especie;
    private String email;
    private String token;
    private Long usuarioId;

    private WebSocketStompClient stompClient;
    private StompSession sesion;

    @BeforeEach
    void registrarUsuarioYAbrirElMapa() throws Exception {
        especie = "especie-test-" + UUID.randomUUID().toString().substring(0, 8);
        email = "mapa-" + UUID.randomUUID().toString().substring(0, 8) + "@test.es";

        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"Ana Prueba","email":"%s","password":"passw0rd123"}
                """.formatted(email));
        assertThat(registro.statusCode()).isEqualTo(201);
        usuarioId = ((Number) JsonPath.read(registro.body(), "$.id")).longValue();

        HttpResponse<String> login = enviar("POST", "/api/auth/login", null, """
                {"email":"%s","password":"passw0rd123"}
                """.formatted(email));
        assertThat(login.statusCode()).isEqualTo(200);
        token = JsonPath.read(login.body(), "$.token");
    }

    @AfterEach
    void limpiar() {
        if (sesion != null && sesion.isConnected()) {
            sesion.disconnect();
        }
        if (stompClient != null) {
            stompClient.stop();
        }
        // El usuario no se puede borrar mientras tenga alertas: hay FK.
        if (usuarioId != null) {
            alertaRepository.deleteAll(alertaRepository.findByUsuarioIdOrderByCreatedAtDesc(usuarioId));
            usuarioRepository.deleteById(usuarioId);
        }
    }

    @Test
    @DisplayName("Publicar, duplicar y cerrar: el mapa recibe los eventos y el duplicado se rechaza con 409")
    void recorridoCompletoDeUnaAlerta() throws Exception {
        BlockingQueue<String> eventos = suscribirseAlMapa(token);

        // 1. Publicación de la alerta -> 201.
        HttpResponse<String> creacion = enviar("POST", "/api/alertas", token, cuerpoAlerta(LATITUD, LONGITUD));
        assertThat(creacion.statusCode()).isEqualTo(201);
        long alertaId = ((Number) JsonPath.read(creacion.body(), "$.id")).longValue();
        assertThat(creacion.headers().firstValue("Location")).get()
                .asString().endsWith("/api/alertas/" + alertaId);

        // 2. El alta llega al canal del mapa sin que el cliente pregunte (RF-04).
        String alta = eventos.poll(ESPERA.toSeconds(), TimeUnit.SECONDS);
        assertThat(alta).as("no llegó ningún evento a %s", AlertaService.CANAL_MAPA).isNotNull();
        assertThat(JsonPath.<String>read(alta, "$.accion")).isEqualTo("ALTA");
        assertThat(((Number) JsonPath.read(alta, "$.alerta.id")).longValue()).isEqualTo(alertaId);
        assertThat(JsonPath.<String>read(alta, "$.alerta.especie")).isEqualTo(especie);
        assertThat(((Number) JsonPath.read(alta, "$.alerta.ubicacion.latitud")).doubleValue())
                .isCloseTo(LATITUD, org.assertj.core.data.Offset.offset(0.00001));
        assertThat(((Number) JsonPath.read(alta, "$.alerta.ubicacion.longitud")).doubleValue())
                .isCloseTo(LONGITUD, org.assertj.core.data.Offset.offset(0.00001));

        // 3. Misma especie a 300 m y en la misma franja de 24 h -> 409 (RF-25).
        HttpResponse<String> duplicada =
                enviar("POST", "/api/alertas", token, cuerpoAlerta(LATITUD_CERCANA, LONGITUD));
        assertThat(duplicada.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(duplicada.body(), "$.codigo")).isEqualTo("ALERTA_DUPLICADA");
        assertThat(((Number) JsonPath.read(duplicada.body(), "$.alertaExistente.id")).longValue())
                .isEqualTo(alertaId);
        assertThat(((Number) JsonPath.read(duplicada.body(), "$.alertaExistente.distanciaMetros")).longValue())
                .isBetween(250L, 350L);

        // El rechazo es real: no se ha guardado nada ni se ha emitido otro evento.
        List<Alerta> suyas = alertaRepository.findByUsuarioIdOrderByCreatedAtDesc(usuarioId);
        assertThat(suyas).hasSize(1);
        assertThat(eventos.poll(1, TimeUnit.SECONDS)).isNull();

        // 4. La alerta aparece en el mapa mientras está ACTIVA.
        HttpResponse<String> mapa = enviar("GET", "/api/alertas/mapa", token, null);
        assertThat(mapa.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(mapa.body(), "$[?(@.id == " + alertaId + ")]")).hasSize(1);

        // 5. Al cerrarla se emite el CIERRE y desaparece del mapa (RF-14).
        HttpResponse<String> cierre = enviar("PUT", "/api/alertas/" + alertaId + "/cerrar", token, null);
        assertThat(cierre.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(cierre.body(), "$.estado")).isEqualTo("ENCONTRADA");

        String eventoCierre = eventos.poll(ESPERA.toSeconds(), TimeUnit.SECONDS);
        assertThat(eventoCierre).as("no llegó el evento de cierre").isNotNull();
        assertThat(JsonPath.<String>read(eventoCierre, "$.accion")).isEqualTo("CIERRE");

        HttpResponse<String> mapaTrasCierre = enviar("GET", "/api/alertas/mapa", token, null);
        assertThat(JsonPath.<List<Object>>read(mapaTrasCierre.body(), "$[?(@.id == " + alertaId + ")]")).isEmpty();
    }

    @Test
    @DisplayName("Sin token no se puede abrir el WebSocket: el mapa no es público")
    void handshakeSinTokenEsRechazado() {
        assertThat(catchThrowable(() -> suscribirseAlMapa(null)))
                .as("el handshake sin token debería fallar")
                .isNotNull();
    }

    @Test
    @DisplayName("Publicar una alerta sin token -> 401")
    void publicarSinTokenDevuelve401() throws Exception {
        HttpResponse<String> respuesta =
                enviar("POST", "/api/alertas", null, cuerpoAlerta(LATITUD, LONGITUD));
        assertThat(respuesta.statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Abre la conexión STOMP y se suscribe a /topic/mapa.
     *
     * Se usa SimpleMessageConverter y se lee el payload como bytes: así la
     * prueba no depende de qué serializador JSON tenga configurado el servidor,
     * y las aserciones se hacen con JsonPath sobre el JSON tal cual viaja por
     * el canal, que es justo lo que verá la aplicación cliente.
     */
    private BlockingQueue<String> suscribirseAlMapa(String token) throws Exception {
        ThreadPoolTaskScheduler planificador = new ThreadPoolTaskScheduler();
        planificador.setPoolSize(1);
        planificador.setThreadNamePrefix("stomp-test-");
        planificador.initialize();

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new SimpleMessageConverter());
        stompClient.setTaskScheduler(planificador);

        // El token viaja como parámetro de consulta, que es la única vía que
        // tiene un navegador: su API WebSocket no permite añadir cabeceras.
        String url = "ws://localhost:" + puerto + "/ws"
                + (token == null ? "" : "?token=" + token);

        sesion = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {})
                .get(ESPERA.toSeconds(), TimeUnit.SECONDS);

        BlockingQueue<String> eventos = new LinkedBlockingQueue<>();
        sesion.subscribe(AlertaService.CANAL_MAPA, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                eventos.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        return eventos;
    }

    private String cuerpoAlerta(double latitud, double longitud) {
        return """
                {
                  "descripcion": "Prueba de extremo a extremo",
                  "especie": "%s",
                  "fechaAvistamiento": "%s",
                  "ubicacion": {"latitud": %s, "longitud": %s},
                  "radioBusqueda": 1000
                }
                """.formatted(especie,
                Instant.now().minus(1, ChronoUnit.HOURS),
                latitud, longitud);
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String token, String cuerpo)
            throws Exception {

        HttpRequest.Builder peticion = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + puerto + ruta))
                .timeout(ESPERA)
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
