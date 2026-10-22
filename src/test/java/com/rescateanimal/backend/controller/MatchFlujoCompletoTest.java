package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.FotosDePrueba;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.LimiteAvistamientoDiarioRepository;
import com.rescateanimal.backend.repository.LimiteFotoDiarioRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.service.AlmacenFotosService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * El límite diario se baja a 2 para poder agotarlo sin hacer 20 peticiones. Solo
 * lo consumen los matches de adopción: el recorrido de los avistamientos hace
 * tres matches sin tocarlo, y el del límite se agota con animales.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890",
                "app.matches.limite-diario=2"
        })
class MatchFlujoCompletoTest {

    private static final int LIMITE = 2;

    /** El día de la cuota es el de Europe/Madrid; ver MatchService.ZONA_CUOTA. */
    private static final ZoneId ZONA_CUOTA = ZoneId.of("Europe/Madrid");

    private static final Duration ESPERA = Duration.ofSeconds(10);

    @LocalServerPort
    private int puerto;

    @Autowired
    private FotoRepository fotoRepository;
    @Autowired
    private LimiteFotoDiarioRepository limiteFotoDiarioRepository;
    @Autowired
    private AlmacenFotosService almacenFotos;

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
    @Autowired
    private LimiteAvistamientoDiarioRepository limiteAvistamientoDiarioRepository;
    @Autowired
    private ProtectoraRepository protectoraRepository;
    @Autowired
    private AnimalAdopcionRepository animalAdopcionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String sufijo;
    /**
     * duenyo: publica la alerta y desliza sus avistamientos. testigo: los
     * registra. curioso: un desconocido, que no puede deslizarlos.
     */
    private String tokenDuenyo;
    private String tokenTestigo;
    private String tokenCurioso;
    private Long idDuenyo;
    private Long idTestigo;
    private Long idCurioso;

    private long alertaId;
    private final List<Long> avistamientos = new ArrayList<>();
    /** Las que publique alguna prueba además de la del escenario. */
    private final List<Long> otrasAlertas = new ArrayList<>();

    /** Solo para las pruebas que moderan: quien retira un avistamiento. */
    private Long idAdministrador;

    /** Solo para la prueba del límite: una protectora con sus animales. */
    private Usuario cuentaProtectora;
    private Protectora protectora;
    private final List<Long> animales = new ArrayList<>();

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

        // Cuatro avistamientos del testigo sobre esa alerta: tres matches, uno
        // más de los que permite el límite, y un descarte.
        for (int i = 0; i < 4; i++) {
            HttpResponse<String> avistamiento = enviar("POST", "/api/avistamientos", tokenTestigo, """
                    {"alertaId":%d,"descripcion":"Lo he visto en el parque %d",
                     "fotoUrl":"%s",
                     "ubicacion":{"latitud":40.4170,"longitud":-3.7040}}
                    """.formatted(alertaId, i, FotosDePrueba.subir(http, puerto, tokenTestigo)));
            assertThat(avistamiento.statusCode()).isEqualTo(201);
            avistamientos.add(numero(avistamiento.body(), "$.id"));
        }
    }

    @AfterEach
    void limpiar() {
        List<Long> usuarios = new ArrayList<>(List.of(idDuenyo, idTestigo, idCurioso));
        if (cuentaProtectora != null) {
            usuarios.add(cuentaProtectora.getId());
        }
        if (idAdministrador != null) {
            usuarios.add(idAdministrador);
        }
        // En orden de dependencia: el chat apunta al match, el match al usuario.
        List<Long> matches = matchRegistroRepository.findAll().stream()
                .filter(m -> usuarios.contains(m.getUsuario().getId()))
                .map(m -> m.getId())
                .toList();
        chatRepository.deleteAll(chatRepository.findByMatchIdIn(matches));
        matchRegistroRepository.deleteAllById(matches);
        notificacionRepository.deleteAll(notificacionRepository.findAll().stream()
                .filter(n -> usuarios.contains(n.getUsuario().getId()))
                .toList());
        limiteMatchDiarioRepository.deleteAll(limiteMatchDiarioRepository.findAll().stream()
                .filter(l -> usuarios.contains(l.getUsuario().getId()))
                .toList());
        // Antes que las alertas y los usuarios: el cupo de avistamientos apunta a los dos.
        limiteAvistamientoDiarioRepository.deleteAll(limiteAvistamientoDiarioRepository.findAll().stream()
                .filter(l -> usuarios.contains(l.getUsuario().getId()))
                .toList());
        avistamientoRepository.deleteAllById(avistamientos);
        alertaRepository.deleteById(alertaId);
        alertaRepository.deleteAllById(otrasAlertas);
        animalAdopcionRepository.deleteAllById(animales);
        if (protectora != null) {
            protectoraRepository.delete(protectora);
        }
        FotosDePrueba.borrarLasDe(usuarios, fotoRepository, limiteFotoDiarioRepository, almacenFotos);
        usuarioRepository.deleteAllById(usuarios);
    }

    @Test
    @DisplayName("El dueño revisa su feed: match con chat y aviso al testigo, doble swipe, descarte, sin cupo")
    void recorridoCompletoDelSwipe() throws Exception {
        long primero = avistamientos.get(0);
        long segundo = avistamientos.get(1);
        long tercero = avistamientos.get(2);
        long cuarto = avistamientos.get(3);

        // 1. El dueño ve los tres en el feed de su alerta; nadie más tiene ese feed.
        assertThat(idsDelFeed(tokenDuenyo)).contains(primero, segundo, tercero);
        assertThat(feed(tokenCurioso).statusCode()).isEqualTo(403);
        assertThat(feed(tokenTestigo).statusCode()).isEqualTo(403);

        // 2. Un desconocido no hace match ni descarta, ni deja rastro.
        for (boolean interesado : new boolean[]{true, false}) {
            HttpResponse<String> ajeno = deslizar(tokenCurioso, primero, interesado);
            assertThat(ajeno.statusCode()).isEqualTo(403);
            assertThat(JsonPath.<String>read(ajeno.body(), "$.codigo")).isEqualTo("ALERTA_AJENA");
        }
        assertThat(matchRegistroRepository.existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                idCurioso, primero, com.rescateanimal.backend.model.enums.TipoPublicacion.AVISTAMIENTO))
                .isFalse();
        assertThat(notificacionesDeMatch(idTestigo)).isEmpty();

        // 3. Match del dueño: 201, chat abierto y aviso para quien registró el
        //    avistamiento, que es el otro extremo del chat recién abierto.
        HttpResponse<String> match = deslizar(tokenDuenyo, primero, true);
        assertThat(match.statusCode()).isEqualTo(201);
        long chatId = numero(match.body(), "$.chatId");
        assertThat(JsonPath.<Boolean>read(match.body(), "$.interesado")).isTrue();
        assertThat(chatRepository.findById(chatId)).isPresent();

        List<Notificacion> avisos = notificacionesDeMatch(idTestigo);
        assertThat(avisos).hasSize(1);
        assertThat(avisos.get(0).getLeida()).isFalse();

        // Y el dueño no recibe nada: es quien acaba de deslizar.
        assertThat(notificacionesDeMatch(idDuenyo)).isEmpty();

        // 4. El mismo swipe otra vez choca con la restricción única.
        assertThat(deslizar(tokenDuenyo, primero, true).statusCode()).isEqualTo(409);

        // 5. Y ya no vuelve a aparecer en el feed.
        assertThat(idsDelFeed(tokenDuenyo)).doesNotContain(primero).contains(segundo, tercero);

        // 6. Revisar avistamientos no gasta cupo: tres matches, uno más de los
        //    que permite el límite diario, y el contador sigue a cero.
        assertThat(deslizar(tokenDuenyo, segundo, true).statusCode()).isEqualTo(201);
        assertThat(deslizar(tokenDuenyo, tercero, true).statusCode()).isEqualTo(201);
        HttpResponse<String> limite = enviar("GET", "/api/matches/limite", tokenDuenyo, null);
        assertThat(numero(limite.body(), "$.consumidos")).isZero();
        assertThat(numero(limite.body(), "$.restantes")).isEqualTo(LIMITE);

        // 7. Un descarte: sin chat, y el avistamiento sale del feed igualmente.
        HttpResponse<String> descarte = deslizar(tokenDuenyo, cuarto, false);
        assertThat(descarte.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<Object>read(descarte.body(), "$")).asString().doesNotContain("chatId");

        // 8. El feed queda vacío: los cuatro están revisados.
        assertThat(idsDelFeed(tokenDuenyo)).doesNotContainAnyElementsOf(avistamientos);

        // 9. Y /mios lista solo los tres matches, no el descarte.
        HttpResponse<String> mios = enviar("GET", "/api/matches/mios", tokenDuenyo, null);
        assertThat(JsonPath.<List<Object>>read(mios.body(), "$")).hasSize(3);
        assertThat(JsonPath.<List<Object>>read(mios.body(), "$[?(@.chatId)]")).hasSize(3);
    }

    @Test
    @DisplayName("El límite diario es de adopción: dos animales lo agotan, el tercero da 429 y los avistamientos siguen")
    void limiteDiarioDeAdopcion() throws Exception {
        crearProtectoraConAnimales(LIMITE + 1);

        // 1. Dos matches de adopción agotan el cupo.
        assertThat(deslizarAnimal(tokenDuenyo, animales.get(0), true).statusCode()).isEqualTo(201);
        HttpResponse<String> limite = enviar("GET", "/api/matches/limite", tokenDuenyo, null);
        assertThat(numero(limite.body(), "$.consumidos")).isEqualTo(1);
        assertThat(numero(limite.body(), "$.restantes")).isEqualTo(LIMITE - 1);
        assertThat(numero(limite.body(), "$.limiteDiario")).isEqualTo(LIMITE);
        assertThat(deslizarAnimal(tokenDuenyo, animales.get(1), true).statusCode()).isEqualTo(201);

        // 2. El tercero choca con el límite, con el cuerpo que pide RF-24.
        HttpResponse<String> pasado = deslizarAnimal(tokenDuenyo, animales.get(2), true);
        assertThat(pasado.statusCode()).isEqualTo(429);
        assertThat(JsonPath.<String>read(pasado.body(), "$.codigo")).isEqualTo("LIMITE_MATCHES_ALCANZADO");
        assertThat(JsonPath.<String>read(pasado.body(), "$.mensaje")).contains("adopción", "mañana");
        assertThat(numero(pasado.body(), "$.limiteDiario")).isEqualTo(LIMITE);
        assertThat(JsonPath.<String>read(pasado.body(), "$.reiniciaEn"))
                .startsWith(LocalDate.now(ZONA_CUOTA).plusDays(1).toString());

        // El rechazo no dejó rastro: ni registro ni cuota de más.
        assertThat(matchRegistroRepository.existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                idDuenyo, animales.get(2), com.rescateanimal.backend.model.enums.TipoPublicacion.ADOPCION))
                .isFalse();
        assertThat(numero(enviar("GET", "/api/matches/limite", tokenDuenyo, null).body(), "$.consumidos"))
                .isEqualTo(LIMITE);

        // 3. Con el cupo agotado, un descarte sigue funcionando y no lo toca.
        assertThat(deslizarAnimal(tokenDuenyo, animales.get(2), false).statusCode()).isEqualTo(201);

        // 4. Y el dueño sigue pudiendo revisar los avistamientos de su alerta.
        HttpResponse<String> avistamiento = deslizar(tokenDuenyo, avistamientos.get(0), true);
        assertThat(avistamiento.statusCode()).isEqualTo(201);
        assertThat(numero(enviar("GET", "/api/matches/limite", tokenDuenyo, null).body(), "$.consumidos"))
                .isEqualTo(LIMITE);
    }

    @Test
    @DisplayName("Nadie desliza lo suyo, y una alerta cerrada no admite ni avistamientos ni swipes")
    void rechazosDeDominio() throws Exception {
        // El testigo intentando deslizar su propio avistamiento: la alerta no es
        // suya, y eso es lo primero que se le dice.
        HttpResponse<String> delTestigo = deslizar(tokenTestigo, avistamientos.get(0), true);
        assertThat(delTestigo.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(delTestigo.body(), "$.codigo")).isEqualTo("ALERTA_AJENA");

        // El dueño intentando deslizar un avistamiento que registró él mismo.
        HttpResponse<String> suyo = enviar("POST", "/api/avistamientos", tokenDuenyo, """
                {"alertaId":%d,"descripcion":"La vi yo desde el balcón","fotoUrl":"%s",
                 "ubicacion":{"latitud":40.4170,"longitud":-3.7040}}
                """.formatted(alertaId, FotosDePrueba.subir(http, puerto, tokenDuenyo)));
        assertThat(suyo.statusCode()).isEqualTo(201);
        avistamientos.add(numero(suyo.body(), "$.id"));
        HttpResponse<String> propio = deslizar(tokenDuenyo, numero(suyo.body(), "$.id"), true);
        assertThat(propio.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(propio.body(), "$.codigo")).isEqualTo("PUBLICACION_PROPIA");

        // Alerta inexistente al registrar un avistamiento. La foto es válida y
        // del testigo, para que lo que falle sea la alerta y no la foto.
        String foto = FotosDePrueba.subir(http, puerto, tokenTestigo);
        HttpResponse<String> sinAlerta = enviar("POST", "/api/avistamientos", tokenTestigo, """
                {"alertaId":999999,"descripcion":"x","fotoUrl":"%s",
                 "ubicacion":{"latitud":40.4,"longitud":-3.7}}
                """.formatted(foto));
        assertThat(sinAlerta.statusCode()).isEqualTo(404);

        // Y una vez cerrada la alerta, deja de admitirlos.
        assertThat(enviar("PUT", "/api/alertas/" + alertaId + "/cerrar", tokenDuenyo, null)
                .statusCode()).isEqualTo(200);

        HttpResponse<String> cerrada = enviar("POST", "/api/avistamientos", tokenTestigo, """
                {"alertaId":%d,"descripcion":"tarde","fotoUrl":"%s",
                 "ubicacion":{"latitud":40.4,"longitud":-3.7}}
                """.formatted(alertaId, foto));
        assertThat(cerrada.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(cerrada.body(), "$.codigo")).isEqualTo("ALERTA_NO_ACTIVA");

        // Tampoco se desliza sobre ellos: el dueño puede tener una tarjeta vieja
        // en pantalla y llamar igualmente.
        HttpResponse<String> swipeTardio = deslizar(tokenDuenyo, avistamientos.get(1), true);
        assertThat(swipeTardio.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(swipeTardio.body(), "$.codigo")).isEqualTo("ALERTA_NO_ACTIVA");

        // Y no dejó rastro: sin registro, y sin gastarle un match del día.
        assertThat(matchRegistroRepository.existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                idDuenyo, avistamientos.get(1),
                com.rescateanimal.backend.model.enums.TipoPublicacion.AVISTAMIENTO)).isFalse();
        assertThat(numero(enviar("GET", "/api/matches/limite", tokenDuenyo, null).body(), "$.consumidos"))
                .isZero();

        // Y el feed de una alerta cerrada ya no se sirve.
        HttpResponse<String> feedCerrado = feed(tokenDuenyo);
        assertThat(feedCerrado.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(feedCerrado.body(), "$.codigo")).isEqualTo("ALERTA_NO_ACTIVA");
    }

    @Test
    @DisplayName("Un avistamiento retirado por el administrador no se desliza: 400 AVISTAMIENTO_ELIMINADO, también con la alerta cerrada")
    void avistamientoRetiradoNoSeDesliza() throws Exception {
        long retirado = avistamientos.get(0);
        String tokenAdmin = crearAdministradorYEntrar();
        assertThat(enviar("DELETE", "/api/admin/publicaciones/" + retirado + "?tipo=AVISTAMIENTO",
                tokenAdmin, null).statusCode()).isEqualTo(204);

        // 1. El dueño puede tener aún la tarjeta en pantalla: ni match ni descarte.
        for (boolean interesado : new boolean[]{true, false}) {
            HttpResponse<String> swipe = deslizar(tokenDuenyo, retirado, interesado);
            assertThat(swipe.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<String>read(swipe.body(), "$.codigo")).isEqualTo("AVISTAMIENTO_ELIMINADO");
        }

        // Sin rastro: ni registro, ni chat, ni aviso al testigo.
        assertThat(matchRegistroRepository.existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                idDuenyo, retirado, com.rescateanimal.backend.model.enums.TipoPublicacion.AVISTAMIENTO))
                .isFalse();
        assertThat(notificacionesDeMatch(idTestigo)).isEmpty();

        // 2. Con la alerta ya cerrada manda el retiro, que es lo propio de la
        //    tarjeta: va antes que el estado de la alerta (§6, paso 4).
        assertThat(enviar("PUT", "/api/alertas/" + alertaId + "/cerrar", tokenDuenyo, null)
                .statusCode()).isEqualTo(200);
        HttpResponse<String> tardio = deslizar(tokenDuenyo, retirado, true);
        assertThat(tardio.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(tardio.body(), "$.codigo")).isEqualTo("AVISTAMIENTO_ELIMINADO");
    }

    @Test
    @DisplayName("Cada usuario registra como mucho 5 avistamientos al día en una misma alerta; otro usuario, u otra alerta, tienen el suyo")
    void cupoDiarioDeAvistamientosPorAlerta() throws Exception {
        // 1. El escenario ya tiene cuatro del testigo en esta alerta: el quinto entra.
        assertThat(avistar(tokenTestigo, alertaId).statusCode()).isEqualTo(201);

        // 2. El sexto no: 429 con el límite y la hora de reinicio.
        HttpResponse<String> sexto = avistar(tokenTestigo, alertaId);
        assertThat(sexto.statusCode()).isEqualTo(429);
        assertThat(JsonPath.<String>read(sexto.body(), "$.codigo")).isEqualTo("LIMITE_AVISTAMIENTOS_ALCANZADO");
        assertThat(numero(sexto.body(), "$.limiteDiario")).isEqualTo(5);
        assertThat(JsonPath.<String>read(sexto.body(), "$.reiniciaEn"))
                .startsWith(LocalDate.now(ZONA_CUOTA).plusDays(1).toString());

        // Sin rastro: el dueño tiene un aviso por cada uno de los cinco, no seis.
        assertThat(avisosDeAvistamiento(alertaId)).isEqualTo(5);

        // 3. Otro usuario en la misma alerta tiene su propio cupo.
        assertThat(avistar(tokenCurioso, alertaId).statusCode()).isEqualTo(201);

        // 4. Y el testigo, en otra alerta, también. Otra especie, para que no
        //    sea un duplicado de la primera (RF-25).
        HttpResponse<String> otra = enviar("POST", "/api/alertas", tokenDuenyo, """
                {"descripcion":"Gata gris","especie":"gato-%s","fechaAvistamiento":"%s",
                 "ubicacion":{"latitud":40.416775,"longitud":-3.703790}}
                """.formatted(sufijo, ahoraMenos(1)));
        assertThat(otra.statusCode()).isEqualTo(201);
        long otraAlerta = numero(otra.body(), "$.id");
        otrasAlertas.add(otraAlerta);
        assertThat(avistar(tokenTestigo, otraAlerta).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("Los avistamientos de una cuenta bloqueada o dada de baja salen del feed, y el swipe sobre ellos da 400 AUTOR_NO_DISPONIBLE")
    void avistamientosDeCuentasNoDisponibles() throws Exception {
        // El curioso también registra uno, y después el administrador lo bloquea.
        HttpResponse<String> delCurioso = avistar(tokenCurioso, alertaId);
        assertThat(delCurioso.statusCode()).isEqualTo(201);
        long deUnaCuentaBloqueada = numero(delCurioso.body(), "$.id");
        String tokenAdmin = crearAdministradorYEntrar();
        assertThat(enviar("PUT", "/api/admin/usuarios/" + idCurioso + "/bloquear", tokenAdmin, """
                {"motivo":"Avistamientos falsos"}
                """).statusCode()).isEqualTo(200);

        // Y el testigo, el de los otros cuatro, se da de baja (RF-19).
        assertThat(enviar("DELETE", "/api/usuarios/me", tokenTestigo, """
                {"password":"passw0rd123"}
                """).statusCode()).isEqualTo(204);

        // 1. El feed del dueño se queda sin ninguno.
        assertThat(idsDelFeed(tokenDuenyo)).isEmpty();

        // 2. Y el swipe sobre cualquiera, en los dos sentidos, da 400 sin dejar rastro.
        for (long deUnaCuentaInactiva : List.of(avistamientos.get(0), deUnaCuentaBloqueada)) {
            for (boolean interesado : new boolean[]{true, false}) {
                HttpResponse<String> swipe = deslizar(tokenDuenyo, deUnaCuentaInactiva, interesado);
                assertThat(swipe.statusCode()).isEqualTo(400);
                assertThat(JsonPath.<String>read(swipe.body(), "$.codigo")).isEqualTo("AUTOR_NO_DISPONIBLE");
            }
        }
        assertThat(matchRegistroRepository.findAll().stream()
                .filter(m -> m.getUsuario().getId().equals(idDuenyo))).isEmpty();
    }

    @Test
    @DisplayName("Sin token, ni feed ni swipe")
    void sinTokenNoHayNada() throws Exception {
        assertThat(feed(null).statusCode()).isEqualTo(401);
        assertThat(enviar("GET", "/api/matches/limite", null, null).statusCode()).isEqualTo(401);
        assertThat(deslizar(null, avistamientos.get(0), true).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("El dueño ve un NUEVO_AVISTAMIENTO por avistamiento en /api/notificaciones y los marca leídos")
    void avisosDeAvistamientoEnLasNotificaciones() throws Exception {
        // 1. Un aviso por cada avistamiento del escenario, apuntando a la alerta.
        HttpResponse<String> avisos = enviar("GET", "/api/notificaciones", tokenDuenyo, null);
        assertThat(avisos.statusCode()).isEqualTo(200);
        assertThat(numero(avisos.body(), "$.totalElements")).isEqualTo(avistamientos.size());
        assertThat(JsonPath.<List<String>>read(avisos.body(), "$.content[*].tipo"))
                .containsOnly("NUEVO_AVISTAMIENTO");
        assertThat(JsonPath.<List<Integer>>read(avisos.body(), "$.content[*].referenciaId"))
                .containsOnly((int) alertaId);
        long primero = numero(avisos.body(), "$.content[0].id");

        // 2. Marcar uno: 204, y deja de salir entre los pendientes.
        assertThat(enviar("PATCH", "/api/notificaciones/" + primero + "/leida", tokenDuenyo, null)
                .statusCode()).isEqualTo(204);
        assertThat(numero(enviar("GET", "/api/notificaciones?leida=false", tokenDuenyo, null).body(),
                "$.totalElements")).isEqualTo(avistamientos.size() - 1);

        // 3. Los avisos de otro no se ven ni se marcan.
        assertThat(numero(enviar("GET", "/api/notificaciones", tokenCurioso, null).body(), "$.totalElements"))
                .isZero();
        HttpResponse<String> ajena = enviar("PATCH", "/api/notificaciones/" + primero + "/leida", tokenCurioso, null);
        assertThat(ajena.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(ajena.body(), "$.codigo")).isEqualTo("ACCESO_DENEGADO");
        assertThat(enviar("PATCH", "/api/notificaciones/999999999/leida", tokenDuenyo, null).statusCode())
                .isEqualTo(404);

        // 4. Y sin token, nada.
        assertThat(enviar("GET", "/api/notificaciones", null, null).statusCode()).isEqualTo(401);
        assertThat(enviar("PATCH", "/api/notificaciones/" + primero + "/leida", null, null).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("El feed global de avistamientos ya no existe: 404 también para un usuario autenticado")
    void elFeedGlobalYaNoExiste() throws Exception {
        HttpResponse<String> global = enviar("GET", "/api/avistamientos/feed", tokenCurioso, null);

        assertThat(global.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(global.body(), "$.codigo")).isEqualTo("NO_ENCONTRADO");
    }

    // ------------------------------------------------------------------

    private List<Notificacion> notificacionesDeMatch(Long usuarioId) {
        return notificacionRepository.findAll().stream()
                .filter(n -> n.getUsuario().getId().equals(usuarioId)
                        && n.getTipo() == TipoNotificacion.NUEVO_MATCH)
                .toList();
    }

    /** Registra un avistamiento con una foto recién subida por quien lo registra. */
    private HttpResponse<String> avistar(String token, long alerta) throws Exception {
        HttpResponse<String> respuesta = enviar("POST", "/api/avistamientos", token, """
                {"alertaId":%d,"descripcion":"Otra vez por el parque","fotoUrl":"%s",
                 "ubicacion":{"latitud":40.4170,"longitud":-3.7040}}
                """.formatted(alerta, FotosDePrueba.subir(http, puerto, token)));
        if (respuesta.statusCode() == 201) {
            avistamientos.add(numero(respuesta.body(), "$.id"));
        }
        return respuesta;
    }

    /** Los NUEVO_AVISTAMIENTO que ha recibido el dueño por esta alerta. */
    private long avisosDeAvistamiento(long alerta) {
        return notificacionRepository.findAll().stream()
                .filter(n -> n.getUsuario().getId().equals(idDuenyo)
                        && n.getTipo() == TipoNotificacion.NUEVO_AVISTAMIENTO
                        && Long.valueOf(alerta).equals(n.getReferenciaId()))
                .count();
    }

    /** El feed de la alerta del escenario, pedido con este token. */
    private HttpResponse<String> feed(String token) throws Exception {
        return enviar("GET", "/api/alertas/" + alertaId + "/avistamientos/feed", token, null);
    }

    private List<Long> idsDelFeed(String token) throws Exception {
        HttpResponse<String> feed = feed(token);
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

    private HttpResponse<String> deslizarAnimal(String token, long animalId, boolean interesado)
            throws Exception {
        return enviar("POST", "/api/matches", token, """
                {"publicacionId":%d,"tipoPublicacion":"ADOPCION","interesado":%s}
                """.formatted(animalId, interesado));
    }

    /**
     * Una protectora ACTIVA con tantos animales DISPONIBLE. Directa a la base
     * de datos: el alta y la aprobación de protectoras tienen su propia prueba,
     * y aquí solo hacen falta animales que deslizar.
     */
    private void crearProtectoraConAnimales(int cuantos) {
        Usuario cuenta = new Usuario();
        cuenta.setNombre("Refugio");
        cuenta.setEmail("protectora-" + sufijo + "@test.es");
        cuenta.setPasswordHash("$2a$10$hashDePrueba");
        cuenta.setRol(Rol.PROTECTORA);
        cuenta.setActivo(true);
        cuentaProtectora = usuarioRepository.save(cuenta);

        Protectora nueva = new Protectora();
        nueva.setUsuario(cuentaProtectora);
        nueva.setNombreOrganizacion("Huellas " + sufijo);
        nueva.setEstado(EstadoProtectora.ACTIVA);
        protectora = protectoraRepository.save(nueva);

        for (int i = 0; i < cuantos; i++) {
            AnimalAdopcion animal = new AnimalAdopcion();
            animal.setProtectora(protectora);
            animal.setNombre("Animal " + i);
            animal.setEspecie("perro");
            animal.setEstado(EstadoAnimal.DISPONIBLE);
            animales.add(animalAdopcionRepository.save(animal).getId());
        }
    }

    /**
     * Un administrador directo a la base de datos, que es la única forma de
     * crearlo, y su token. Lo que se prueba es lo que hace al moderar.
     */
    private String crearAdministradorYEntrar() throws Exception {
        Usuario admin = new Usuario();
        admin.setNombre("Administrador");
        admin.setEmail("admin-" + sufijo + "@test.es");
        admin.setPasswordHash(passwordEncoder.encode("passw0rd123"));
        admin.setRol(Rol.ADMIN);
        admin.setActivo(true);
        idAdministrador = usuarioRepository.save(admin).getId();

        HttpResponse<String> login = enviar("POST", "/api/auth/login", null, """
                {"email":"%s","password":"passw0rd123"}
                """.formatted(admin.getEmail()));
        assertThat(login.statusCode()).isEqualTo(200);
        return JsonPath.read(login.body(), "$.token");
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
        return Instant.now().minus(horas, ChronoUnit.HOURS).toString();
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
