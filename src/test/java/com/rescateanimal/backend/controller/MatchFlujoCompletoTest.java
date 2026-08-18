package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorrido completo de avistamientos y match contra el servidor y PostgreSQL
 * reales (RF-08, RF-15, RF-21, RF-24).
 *
 * Existe sobre todo por el contador diario: el INSERT ... ON CONFLICT DO NOTHING
 * y el UPDATE condicional de LimiteMatchDiarioRepository son SQL nativo, y con
 * repositorios simulados nadie comprobaría que ese SQL siquiera es válido.
 * Igual pasa con la restricción única de MATCH_REGISTRO, que es lo que sostiene
 * el 409 y el "no repetir en el feed".
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d") y
 * perfil "local" activo, como BackendApplicationTests.
 *
 * El límite diario se baja a 2 para poder agotarlo sin hacer 20 peticiones.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890",
                "app.matches.limite-diario=2"
        })
class MatchFlujoCompletoTest {

    private static final int LIMITE = 2;
    private static final Duration ESPERA = Duration.ofSeconds(10);

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
    private NotificacionRepository notificacionRepository;
    @Autowired
    private LimiteMatchDiarioRepository limiteMatchDiarioRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    private String sufijo;
    /** duenyo: publica la alerta. testigo: registra avistamientos. curioso: desliza. */
    private String tokenDuenyo;
    private String tokenTestigo;
    private String tokenCurioso;
    private Long idDuenyo;
    private Long idTestigo;
    private Long idCurioso;

    private long alertaId;
    private final List<Long> avistamientos = new ArrayList<>();

    @BeforeEach
    void prepararEscenario() throws Exception {
        sufijo = UUID.randomUUID().toString().substring(0, 8);

        long[] duenyo = registrarYEntrar("duenyo");
        idDuenyo = duenyo[0];
        long[] testigo = registrarYEntrar("testigo");
        idTestigo = testigo[0];
        long[] curioso = registrarYEntrar("curioso");
        idCurioso = curioso[0];

        HttpResponse<String> alerta = enviar("POST", "/api/alertas", tokenDuenyo, """
                {"descripcion":"Perro marrón con collar rojo","especie":"perro-%s",
                 "fechaAvistamiento":"%s",
                 "ubicacion":{"latitud":40.416775,"longitud":-3.703790}}
                """.formatted(sufijo, ahoraMenos(1)));
        assertThat(alerta.statusCode()).isEqualTo(201);
        alertaId = numero(alerta.body(), "$.id");

        // Tres avistamientos del testigo sobre esa alerta: dos para agotar la
        // cuota del curioso y uno más para chocar contra el límite.
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> avistamiento = enviar("POST", "/api/avistamientos", tokenTestigo, """
                    {"alertaId":%d,"descripcion":"Lo he visto en el parque %d",
                     "fotoUrl":"https://fotos/%s-%d.jpg",
                     "ubicacion":{"latitud":40.4170,"longitud":-3.7040}}
                    """.formatted(alertaId, i, sufijo, i));
            assertThat(avistamiento.statusCode()).isEqualTo(201);
            avistamientos.add(numero(avistamiento.body(), "$.id"));
        }
    }

    @AfterEach
    void limpiar() {
        // En orden de dependencia: el chat apunta al match, el match al usuario.
        chatRepository.deleteAll(chatRepository.findByMatchIdIn(
                matchRegistroRepository.findAll().stream()
                        .filter(m -> avistamientos.contains(m.getPublicacionId()))
                        .map(m -> m.getId())
                        .toList()));
        matchRegistroRepository.deleteAll(matchRegistroRepository.findAll().stream()
                .filter(m -> avistamientos.contains(m.getPublicacionId()))
                .toList());
        notificacionRepository.deleteAll(notificacionRepository.findAll().stream()
                .filter(n -> List.of(idDuenyo, idTestigo, idCurioso).contains(n.getUsuario().getId()))
                .toList());
        limiteMatchDiarioRepository.deleteAll(limiteMatchDiarioRepository.findAll().stream()
                .filter(l -> List.of(idDuenyo, idTestigo, idCurioso).contains(l.getUsuario().getId()))
                .toList());
        avistamientoRepository.deleteAllById(avistamientos);
        alertaRepository.deleteById(alertaId);
        usuarioRepository.deleteAllById(List.of(idDuenyo, idTestigo, idCurioso));
    }

    @Test
    @DisplayName("Feed, match con chat y notificación, doble swipe, descarte y límite diario")
    void recorridoCompletoDelSwipe() throws Exception {
        long primero = avistamientos.get(0);
        long segundo = avistamientos.get(1);
        long tercero = avistamientos.get(2);

        // 1. El curioso ve los tres candidatos; el testigo no ve los suyos.
        assertThat(idsDelFeed(tokenCurioso)).contains(primero, segundo, tercero);
        assertThat(idsDelFeed(tokenTestigo)).doesNotContain(primero, segundo, tercero);

        // 2. Match: 201, chat abierto y notificación para quien publicó la alerta.
        HttpResponse<String> match = deslizar(tokenCurioso, primero, true);
        assertThat(match.statusCode()).isEqualTo(201);
        long chatId = numero(match.body(), "$.chatId");
        assertThat(JsonPath.<Boolean>read(match.body(), "$.interesado")).isTrue();
        assertThat(chatRepository.findById(chatId)).isPresent();

        List<Notificacion> avisos = notificacionRepository.findAll().stream()
                .filter(n -> n.getUsuario().getId().equals(idDuenyo)
                        && n.getTipo() == TipoNotificacion.NUEVO_MATCH)
                .toList();
        assertThat(avisos).hasSize(1);
        assertThat(avisos.get(0).getLeida()).isFalse();

        // 3. El mismo swipe otra vez choca con la restricción única.
        assertThat(deslizar(tokenCurioso, primero, true).statusCode()).isEqualTo(409);

        // 4. Y ya no vuelve a aparecer en el feed.
        assertThat(idsDelFeed(tokenCurioso)).doesNotContain(primero).contains(segundo, tercero);

        // 5. El contador va por uno de dos.
        HttpResponse<String> limite = enviar("GET", "/api/matches/limite", tokenCurioso, null);
        assertThat(numero(limite.body(), "$.consumidos")).isEqualTo(1);
        assertThat(numero(limite.body(), "$.restantes")).isEqualTo(LIMITE - 1);
        assertThat(numero(limite.body(), "$.limiteDiario")).isEqualTo(LIMITE);

        // 6. Segundo match: cuota agotada.
        assertThat(deslizar(tokenCurioso, segundo, true).statusCode()).isEqualTo(201);

        // 7. El tercero choca con el límite diario, con el cuerpo que pide RF-24.
        HttpResponse<String> pasado = deslizar(tokenCurioso, tercero, true);
        assertThat(pasado.statusCode()).isEqualTo(429);
        assertThat(JsonPath.<String>read(pasado.body(), "$.codigo")).isEqualTo("LIMITE_MATCHES_ALCANZADO");
        assertThat(JsonPath.<String>read(pasado.body(), "$.mensaje")).contains("mañana");
        assertThat(numero(pasado.body(), "$.limiteDiario")).isEqualTo(LIMITE);
        assertThat(JsonPath.<String>read(pasado.body(), "$.reiniciaEn"))
                .startsWith(LocalDate.now().plusDays(1).toString());

        // El rechazo no dejó rastro: ni registro ni cuota de más.
        assertThat(matchRegistroRepository
                .existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                        idCurioso, tercero, com.rescateanimal.backend.model.enums.TipoPublicacion.AVISTAMIENTO))
                .isFalse();
        assertThat(numero(enviar("GET", "/api/matches/limite", tokenCurioso, null).body(), "$.consumidos"))
                .isEqualTo(LIMITE);

        // 8. Con la cuota agotada, un descarte sigue funcionando y no la toca.
        HttpResponse<String> descarte = deslizar(tokenCurioso, tercero, false);
        assertThat(descarte.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Object>read(descarte.body(), "$")).asString().doesNotContain("chatId");
        assertThat(numero(enviar("GET", "/api/matches/limite", tokenCurioso, null).body(), "$.consumidos"))
                .isEqualTo(LIMITE);

        // 9. El feed queda vacío: los tres están deslizados.
        assertThat(idsDelFeed(tokenCurioso)).doesNotContain(primero, segundo, tercero);

        // 10. Y /mios lista solo los dos matches, no el descarte.
        HttpResponse<String> mios = enviar("GET", "/api/matches/mios", tokenCurioso, null);
        assertThat(JsonPath.<List<Object>>read(mios.body(), "$")).hasSize(2);
        assertThat(JsonPath.<List<Object>>read(mios.body(), "$[?(@.chatId)]")).hasSize(2);
    }

    @Test
    @DisplayName("Nadie desliza lo suyo, y una alerta cerrada no admite ni avistamientos ni swipes")
    void rechazosDeDominio() throws Exception {
        // El testigo intentando deslizar su propio avistamiento.
        HttpResponse<String> propio = deslizar(tokenTestigo, avistamientos.get(0), true);
        assertThat(propio.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(propio.body(), "$.codigo")).isEqualTo("PUBLICACION_PROPIA");

        // Alerta inexistente al registrar un avistamiento.
        HttpResponse<String> sinAlerta = enviar("POST", "/api/avistamientos", tokenTestigo, """
                {"alertaId":999999,"descripcion":"x","fotoUrl":"https://f/x.jpg",
                 "ubicacion":{"latitud":40.4,"longitud":-3.7}}
                """);
        assertThat(sinAlerta.statusCode()).isEqualTo(404);

        // Y una vez cerrada la alerta, deja de admitirlos.
        assertThat(enviar("PUT", "/api/alertas/" + alertaId + "/cerrar", tokenDuenyo, null)
                .statusCode()).isEqualTo(200);

        HttpResponse<String> cerrada = enviar("POST", "/api/avistamientos", tokenTestigo, """
                {"alertaId":%d,"descripcion":"tarde","fotoUrl":"https://f/x.jpg",
                 "ubicacion":{"latitud":40.4,"longitud":-3.7}}
                """.formatted(alertaId));
        assertThat(cerrada.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(cerrada.body(), "$.codigo")).isEqualTo("ALERTA_NO_ACTIVA");

        // Tampoco se desliza sobre ellos: el feed ya no los ofrece, pero el
        // curioso puede tener una tarjeta vieja en pantalla y llamar igualmente.
        HttpResponse<String> swipeTardio = deslizar(tokenCurioso, avistamientos.get(1), true);
        assertThat(swipeTardio.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(swipeTardio.body(), "$.codigo")).isEqualTo("ALERTA_NO_ACTIVA");

        // Y no dejó rastro: sin registro, y sin gastarle un match del día.
        assertThat(matchRegistroRepository.existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                idCurioso, avistamientos.get(1),
                com.rescateanimal.backend.model.enums.TipoPublicacion.AVISTAMIENTO)).isFalse();
        assertThat(numero(enviar("GET", "/api/matches/limite", tokenCurioso, null).body(), "$.consumidos"))
                .isZero();

        // Y el feed se vacía: sus avistamientos colgaban de una alerta ya cerrada.
        assertThat(idsDelFeed(tokenCurioso)).doesNotContainAnyElementsOf(avistamientos);
    }

    @Test
    @DisplayName("Sin token, ni feed ni swipe")
    void sinTokenNoHayNada() throws Exception {
        assertThat(enviar("GET", "/api/avistamientos/feed", null, null).statusCode()).isEqualTo(401);
        assertThat(enviar("GET", "/api/matches/limite", null, null).statusCode()).isEqualTo(401);
        assertThat(deslizar(null, avistamientos.get(0), true).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------

    private List<Long> idsDelFeed(String token) throws Exception {
        HttpResponse<String> feed = enviar("GET", "/api/avistamientos/feed", token, null);
        assertThat(feed.statusCode()).isEqualTo(200);
        return JsonPath.<List<Integer>>read(feed.body(), "$[*].id")
                .stream().map(Integer::longValue).toList();
    }

    private HttpResponse<String> deslizar(String token, long publicacionId, boolean interesado)
            throws Exception {
        return enviar("POST", "/api/matches", token, """
                {"publicacionId":%d,"tipoPublicacion":"AVISTAMIENTO","interesado":%s}
                """.formatted(publicacionId, interesado));
    }

    /** Registra un usuario nuevo, inicia sesión y guarda su token. Devuelve su id. */
    private long[] registrarYEntrar(String papel) throws Exception {
        String email = papel + "-" + sufijo + "@test.es";

        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"%s","email":"%s","password":"passw0rd123"}
                """.formatted(papel, email));
        assertThat(registro.statusCode()).isEqualTo(201);

        HttpResponse<String> login = enviar("POST", "/api/auth/login", null, """
                {"email":"%s","password":"passw0rd123"}
                """.formatted(email));
        assertThat(login.statusCode()).isEqualTo(200);

        String token = JsonPath.read(login.body(), "$.token");
        switch (papel) {
            case "duenyo" -> tokenDuenyo = token;
            case "testigo" -> tokenTestigo = token;
            default -> tokenCurioso = token;
        }
        return new long[]{numero(registro.body(), "$.id")};
    }

    private static long numero(String json, String ruta) {
        return ((Number) JsonPath.read(json, ruta)).longValue();
    }

    private static String ahoraMenos(int horas) {
        return LocalDateTime.now().minusHours(horas).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
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
