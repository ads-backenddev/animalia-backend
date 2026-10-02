package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.FotosDePrueba;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.LimiteFotoDiarioRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.MensajeRepository;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.service.AlertaService;
import com.rescateanimal.backend.service.AlmacenFotosService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los endpoints de alerta del bloque §22 de extremo a extremo: servidor real,
 * PostgreSQL real y el mapa por STOMP.
 *
 * Lo que solo se puede probar así:
 * - que retirar una alerta NO toca lo que cuelga de ella: el chat abierto sobre
 *   uno de sus avistamientos sigue legible y sigue admitiendo mensajes;
 * - que ACTUALIZACION y CIERRE llegan de verdad por /topic/mapa;
 * - que el dueño no deshace la moderación ni cerrando ni retirando.
 *
 * Cada prueba usa una especie aleatoria y un punto lejos de los de otras
 * pruebas, para que RF-25 no choque con alertas ajenas, y cada usuario un área
 * propia, para que publicar no avise a usuarios de otras pruebas.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertasFlujoCompletoTest {

    private static final Duration ESPERA = Duration.ofSeconds(10);
    private static final String CLAVE = "passw0rd123";

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private AvistamientoRepository avistamientoRepository;
    @Autowired
    private MatchRegistroRepository matchRegistroRepository;
    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private MensajeRepository mensajeRepository;
    @Autowired
    private NotificacionRepository notificacionRepository;
    @Autowired
    private LimiteMatchDiarioRepository limiteMatchDiarioRepository;
    @Autowired
    private FotoRepository fotoRepository;
    @Autowired
    private LimiteFotoDiarioRepository limiteFotoDiarioRepository;
    @Autowired
    private AlmacenFotosService almacenFotos;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> usuarios = new ArrayList<>();

    private String especie;
    private double latitud;
    private double longitud;
    private String tokenDuenyo;
    private String tokenTestigo;

    private WebSocketStompClient stompClient;
    private StompSession sesion;

    @BeforeEach
    void preparar() throws Exception {
        especie = "flujo-" + UUID.randomUUID().toString().substring(0, 8);
        // Un punto propio de esta ejecución, a unos 50 km de Madrid.
        latitud = 40.85 + Math.random() / 10;
        longitud = -3.20 - Math.random() / 10;
        tokenDuenyo = entrar(registrar("duenyo"));
        tokenTestigo = entrar(registrar("testigo"));
    }

    @AfterEach
    void limpiar() {
        if (sesion != null && sesion.isConnected()) {
            sesion.disconnect();
        }
        if (stompClient != null) {
            stompClient.stop();
        }
        // En orden de dependencia. getX().getId() no inicializa el proxy, así que
        // es seguro sobre entidades ya desconectadas.
        List<Long> matches = matchRegistroRepository.findAll().stream()
                .filter(m -> usuarios.contains(m.getUsuario().getId())).map(m -> m.getId()).toList();
        List<Long> chats = chatRepository.findByMatchIdIn(matches).stream().map(c -> c.getId()).toList();
        mensajeRepository.deleteAll(mensajeRepository.findAll().stream()
                .filter(m -> chats.contains(m.getChat().getId())).toList());
        chatRepository.deleteAllById(chats);
        matchRegistroRepository.deleteAllById(matches);
        notificacionRepository.deleteAll(notificacionRepository.findAll().stream()
                .filter(n -> usuarios.contains(n.getUsuario().getId())).toList());
        limiteMatchDiarioRepository.deleteAll(limiteMatchDiarioRepository.findAll().stream()
                .filter(l -> usuarios.contains(l.getUsuario().getId())).toList());
        avistamientoRepository.deleteAll(avistamientoRepository.findAll().stream()
                .filter(a -> usuarios.contains(a.getUsuario().getId())).toList());
        alertaRepository.deleteAll(alertaRepository.findAll().stream()
                .filter(a -> usuarios.contains(a.getUsuario().getId())).toList());
        FotosDePrueba.borrarLasDe(usuarios, fotoRepository, limiteFotoDiarioRepository, almacenFotos);
        usuarioRepository.deleteAllById(usuarios);
    }

    @Test
    @DisplayName("Publicar, editar, listar, ver y retirar; el chat sobre su avistamiento sobrevive a la retirada")
    void cicloCompleto() throws Exception {
        BlockingQueue<String> mapa = suscribirseAlMapa(tokenDuenyo);
        long alerta = publicar(tokenDuenyo, latitud, longitud);
        assertThat(eventoDe(mapa, alerta)).as("ALTA").isEqualTo("ALTA");

        // Un testigo la ve y el dueño desliza a favor: chat con un mensaje.
        long avistamiento = numero(enviar("POST", "/api/avistamientos", tokenTestigo, """
                {"alertaId":%d,"descripcion":"La he visto en la gasolinera","fotoUrl":"%s",
                 "ubicacion":{"latitud":%s,"longitud":%s}}
                """.formatted(alerta, FotosDePrueba.subir(http, puerto, tokenTestigo), latitud, longitud)), "$.id");
        assertThat(enviar("POST", "/api/matches", tokenDuenyo, """
                {"publicacionId":%d,"tipoPublicacion":"AVISTAMIENTO","interesado":true}
                """.formatted(avistamiento)).statusCode()).isEqualTo(201);
        long chat = numero(enviar("GET", "/api/chats", tokenDuenyo, null), "$[0].id");
        assertThat(enviar("POST", "/api/chats/" + chat + "/mensajes", tokenTestigo, """
                {"contenido":"Estaba junto a los surtidores"}
                """).statusCode()).isEqualTo(201);

        // Editar: mover el punto ~300 m y corregir la descripción.
        HttpResponse<String> editada = enviar("PUT", "/api/alertas/" + alerta, tokenDuenyo,
                cuerpo("Perra marrón, collar rojo y chapa", latitud + 0.003, longitud));
        assertThat(editada.statusCode()).isEqualTo(200);
        assertThat(eventoDe(mapa, alerta)).as("ACTUALIZACION").isEqualTo("ACTUALIZACION");

        // Listado y detalle.
        HttpResponse<String> listado = enviar("GET", "/api/alertas?especie=" + especie, tokenTestigo, null);
        assertThat(JsonPath.<List<Integer>>read(listado.body(), "$.content[*].id")).containsExactly((int) alerta);
        HttpResponse<String> detalle = enviar("GET", "/api/alertas/" + alerta, tokenTestigo, null);
        assertThat(detalle.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(detalle.body(), "$.descripcion")).isEqualTo("Perra marrón, collar rojo y chapa");
        assertThat(JsonPath.<List<Integer>>read(detalle.body(), "$.avistamientos[*].id"))
                .containsExactly((int) avistamiento);
        assertThat(detalle.body()).doesNotContain("@test.es");

        // Retirar.
        assertThat(enviar("DELETE", "/api/alertas/" + alerta, tokenDuenyo, null).statusCode()).isEqualTo(204);
        assertThat(eventoDe(mapa, alerta)).as("CIERRE").isEqualTo("CIERRE");

        HttpResponse<String> retirada = enviar("GET", "/api/alertas/" + alerta, tokenTestigo, null);
        assertThat(retirada.statusCode()).as("terminada no es lo mismo que no encontrada").isEqualTo(200);
        assertThat(JsonPath.<String>read(retirada.body(), "$.estado")).isEqualTo("RETIRADA");
        assertThat(JsonPath.<Object>read(retirada.body(), "$.descripcion")).isNull();
        assertThat(JsonPath.<List<Object>>read(retirada.body(), "$.avistamientos")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(
                enviar("GET", "/api/alertas?especie=" + especie, tokenTestigo, null).body(), "$.content")).isEmpty();
        assertThat(JsonPath.<String>read(
                enviar("PUT", "/api/alertas/" + alerta, tokenDuenyo, cuerpo("Otra", latitud, longitud)).body(),
                "$.codigo")).isEqualTo("ALERTA_NO_ACTIVA");

        // Y la conversación sigue: se lee entera y admite mensajes nuevos.
        HttpResponse<String> mensajes = enviar("GET", "/api/chats/" + chat + "/mensajes", tokenDuenyo, null);
        assertThat(mensajes.statusCode()).isEqualTo(200);
        assertThat(mensajes.body()).contains("Estaba junto a los surtidores");
        assertThat(enviar("POST", "/api/chats/" + chat + "/mensajes", tokenDuenyo, """
                {"contenido":"Ya la encontramos, gracias"}
                """).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("La moderación quita la alerta del mapa, y el dueño no la deshace ni cerrando ni retirando")
    void moderacion() throws Exception {
        String tokenAdmin = entrar(crearAdmin());
        BlockingQueue<String> mapa = suscribirseAlMapa(tokenDuenyo);
        long alerta = publicar(tokenDuenyo, latitud, longitud);
        assertThat(eventoDe(mapa, alerta)).isEqualTo("ALTA");

        assertThat(enviar("DELETE", "/api/admin/publicaciones/" + alerta + "?tipo=ALERTA", tokenAdmin, null)
                .statusCode()).isEqualTo(204);
        assertThat(eventoDe(mapa, alerta)).as("antes no se emitía").isEqualTo("CIERRE");

        HttpResponse<String> cerrar = enviar("PUT", "/api/alertas/" + alerta + "/cerrar", tokenDuenyo, null);
        assertThat(cerrar.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(cerrar.body(), "$.codigo")).isEqualTo("ALERTA_NO_ACTIVA");
        assertThat(enviar("DELETE", "/api/alertas/" + alerta, tokenDuenyo, null).statusCode()).isEqualTo(204);

        HttpResponse<String> detalle = enviar("GET", "/api/alertas/" + alerta, tokenTestigo, null);
        assertThat(JsonPath.<String>read(detalle.body(), "$.estado"))
                .as("ni cerrar ni retirar sobrescriben la moderación")
                .isEqualTo("ELIMINADA");
        assertThat(JsonPath.<Object>read(detalle.body(), "$.descripcion")).isNull();
    }

    @Test
    @DisplayName("Mover una alerta encima de otra de la misma especie es 409: RF-25 no se esquiva editando")
    void rf25AlEditar() throws Exception {
        long primera = publicar(tokenDuenyo, latitud, longitud);
        long segunda = publicar(tokenTestigo, latitud + 0.05, longitud);

        HttpResponse<String> encima = enviar("PUT", "/api/alertas/" + segunda, tokenTestigo,
                cuerpo("Movida al mismo sitio", latitud + 0.001, longitud));

        assertThat(encima.statusCode()).isEqualTo(409);
        assertThat(numero(encima, "$.alertaExistente.id")).isEqualTo(primera);
        assertThat(enviar("PUT", "/api/alertas/" + segunda, tokenTestigo,
                cuerpo("Solo cambia el texto", latitud + 0.05, longitud)).statusCode())
                .as("sin mover la alerta no se vuelve a comprobar")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------

    private long publicar(String token, double lat, double lng) throws Exception {
        HttpResponse<String> alta = enviar("POST", "/api/alertas", token, cuerpo("Perra marrón con collar rojo", lat, lng));
        assertThat(alta.statusCode()).as(alta.body()).isEqualTo(201);
        return numero(alta, "$.id");
    }

    private String cuerpo(String descripcion, double lat, double lng) {
        return """
                {"descripcion":"%s","especie":"%s","fechaAvistamiento":"%s",
                 "ubicacion":{"latitud":%s,"longitud":%s},"radioBusqueda":1000}
                """.formatted(descripcion, especie, Instant.now().minus(1, ChronoUnit.HOURS), lat, lng);
    }

    /** La acción del siguiente evento del mapa sobre esta alerta, ignorando los de otras. */
    private static String eventoDe(BlockingQueue<String> mapa, long alerta) throws Exception {
        long limite = System.nanoTime() + ESPERA.toNanos();
        while (System.nanoTime() < limite) {
            String evento = mapa.poll(1, TimeUnit.SECONDS);
            if (evento != null && ((Number) JsonPath.read(evento, "$.alerta.id")).longValue() == alerta) {
                return JsonPath.read(evento, "$.accion");
            }
        }
        return null;
    }

    private BlockingQueue<String> suscribirseAlMapa(String token) throws Exception {
        ThreadPoolTaskScheduler planificador = new ThreadPoolTaskScheduler();
        planificador.setPoolSize(1);
        planificador.initialize();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new SimpleMessageConverter());
        stompClient.setTaskScheduler(planificador);
        sesion = stompClient.connectAsync("ws://localhost:" + puerto + "/ws?token=" + token,
                new StompSessionHandlerAdapter() {}).get(ESPERA.toSeconds(), TimeUnit.SECONDS);

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

    private String registrar(String papel) throws Exception {
        String email = papel + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.es";
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"%s","email":"%s","password":"%s","areaGeografica":"Flujo %s"}
                """.formatted(papel, email, CLAVE, email));
        assertThat(registro.statusCode()).isEqualTo(201);
        usuarios.add(numero(registro, "$.id"));
        return email;
    }

    private String crearAdmin() {
        Usuario admin = new Usuario();
        admin.setNombre("Administrador");
        admin.setEmail("admin-" + UUID.randomUUID().toString().substring(0, 8) + "@test.es");
        admin.setPasswordHash(passwordEncoder.encode(CLAVE));
        admin.setRol(Rol.ADMIN);
        admin.setActivo(true);
        usuarios.add(usuarioRepository.save(admin).getId());
        return admin.getEmail();
    }

    private String entrar(String email) throws Exception {
        HttpResponse<String> login = enviar("POST", "/api/auth/login", null, """
                {"email":"%s","password":"%s"}
                """.formatted(email, CLAVE));
        assertThat(login.statusCode()).isEqualTo(200);
        return JsonPath.read(login.body(), "$.token");
    }

    private static long numero(HttpResponse<String> respuesta, String ruta) {
        return ((Number) JsonPath.read(respuesta.body(), ruta)).longValue();
    }

    private HttpResponse<String> enviar(String metodo, String ruta, String token, String cuerpo) throws Exception {
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
