package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * El techo del número de página, en los nueve listados paginados de la API y
 * contra el servidor real: una página por encima responde 400
 * PARAMETRO_INVALIDO, y la de ?page=2147483647, que antes desbordaba el
 * desplazamiento de Spring Data y acababa en 500 (informe ENS, C16), también.
 * La propia página máxima se sirve.
 *
 * Cada listado se pide con alguien que puede verlo, porque los permisos van
 * antes que la página: el administrador, la protectora en su panel, el dueño
 * de la alerta en sus descartados y un participante en el chat.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890")
class PaginaMaximaTest {

    /** Paginas.PAGINA_MAXIMA, que es del paquete de los services. */
    private static final int PAGINA_MAXIMA = 10_000;
    private static final String CONTRASENYA = "passw0rd123";
    private static final Duration ESPERA = Duration.ofSeconds(10);

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private ProtectoraRepository protectoraRepository;
    @Autowired
    private AnimalAdopcionRepository animalAdopcionRepository;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private MatchRegistroRepository matchRegistroRepository;
    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Usuario> usuarios = new ArrayList<>();

    private String tokenCiudadano;
    private String tokenProtectora;
    private String tokenAdministrador;
    private Protectora protectora;
    private AnimalAdopcion animal;
    private Alerta alerta;
    private MatchRegistro match;
    private Chat chat;

    /** Un listado y quien puede pedirlo. */
    private record Listado(String ruta, String token) {
    }

    @BeforeEach
    void preparar() throws Exception {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        Usuario ciudadano = usuario("ciudadano-" + sufijo, Rol.CIUDADANO);
        Usuario cuentaProtectora = usuario("protectora-" + sufijo, Rol.PROTECTORA);
        Usuario administrador = usuario("admin-" + sufijo, Rol.ADMIN);

        Protectora nueva = new Protectora();
        nueva.setUsuario(cuentaProtectora);
        nueva.setNombreOrganizacion("Paginas " + sufijo);
        nueva.setEstado(EstadoProtectora.ACTIVA);
        protectora = protectoraRepository.save(nueva);

        AnimalAdopcion nuevoAnimal = new AnimalAdopcion();
        nuevoAnimal.setProtectora(protectora);
        nuevoAnimal.setNombre("Animal");
        nuevoAnimal.setEspecie("perro");
        nuevoAnimal.setEstado(EstadoAnimal.DISPONIBLE);
        animal = animalAdopcionRepository.save(nuevoAnimal);

        Alerta nuevaAlerta = new Alerta();
        nuevaAlerta.setUsuario(ciudadano);
        nuevaAlerta.setDescripcion("Para las páginas");
        nuevaAlerta.setEspecie("paginas-" + sufijo);
        nuevaAlerta.setFechaAvistamiento(Instant.now());
        nuevaAlerta.setUltimaUbicacion(new GeometryFactory(new PrecisionModel(), 4326)
                .createPoint(new Coordinate(-3.70, 40.41)));
        nuevaAlerta.setEstado(EstadoAlerta.ACTIVA);
        alerta = alertaRepository.save(nuevaAlerta);

        // Directo a la base de datos: el match y su chat tienen sus pruebas, y
        // aquí solo hace falta un chat del que el ciudadano sea participante.
        MatchRegistro nuevoMatch = new MatchRegistro();
        nuevoMatch.setUsuario(ciudadano);
        nuevoMatch.setPublicacionId(animal.getId());
        nuevoMatch.setTipoPublicacion(TipoPublicacion.ADOPCION);
        nuevoMatch.setInteresado(true);
        match = matchRegistroRepository.save(nuevoMatch);
        Chat nuevoChat = new Chat();
        nuevoChat.setMatch(match);
        chat = chatRepository.save(nuevoChat);

        tokenCiudadano = entrar(ciudadano);
        tokenProtectora = entrar(cuentaProtectora);
        tokenAdministrador = entrar(administrador);
    }

    @AfterEach
    void limpiar() {
        chatRepository.delete(chat);
        matchRegistroRepository.delete(match);
        alertaRepository.delete(alerta);
        animalAdopcionRepository.delete(animal);
        protectoraRepository.delete(protectora);
        usuarioRepository.deleteAll(usuarios);
    }

    @Test
    @DisplayName("En los nueve listados, una página por encima del máximo da 400 PARAMETRO_INVALIDO, y la página máxima se sirve")
    void techoDelNumeroDePagina() throws Exception {
        SoftAssertions comprobaciones = new SoftAssertions();
        for (Listado listado : listados()) {
            for (long pagina : List.of((long) PAGINA_MAXIMA + 1, (long) Integer.MAX_VALUE)) {
                HttpResponse<String> respuesta = pedir(listado, pagina);
                comprobaciones.assertThat(respuesta.statusCode())
                        .as("%s?page=%d", listado.ruta(), pagina).isEqualTo(400);
                if (respuesta.statusCode() == 400) {
                    comprobaciones.assertThat(JsonPath.<String>read(respuesta.body(), "$.codigo"))
                            .as("%s?page=%d", listado.ruta(), pagina).isEqualTo("PARAMETRO_INVALIDO");
                }
            }
            comprobaciones.assertThat(pedir(listado, PAGINA_MAXIMA).statusCode())
                    .as("%s?page=%d", listado.ruta(), PAGINA_MAXIMA).isEqualTo(200);
        }
        comprobaciones.assertAll();
    }

    private List<Listado> listados() {
        return List.of(
                new Listado("/api/alertas", tokenCiudadano),
                new Listado("/api/alertas/" + alerta.getId() + "/avistamientos/descartados", tokenCiudadano),
                new Listado("/api/notificaciones", tokenCiudadano),
                new Listado("/api/chats/" + chat.getId() + "/mensajes", tokenCiudadano),
                new Listado("/api/adopcion", tokenCiudadano),
                new Listado("/api/protectoras", tokenCiudadano),
                new Listado("/api/protectoras/" + protectora.getId(), tokenCiudadano),
                new Listado("/api/protectoras/panel/animales", tokenProtectora),
                new Listado("/api/admin/usuarios", tokenAdministrador));
    }

    private Usuario usuario(String nombre, Rol rol) {
        Usuario nuevo = new Usuario();
        nuevo.setNombre(nombre);
        nuevo.setEmail(nombre + "@test.es");
        nuevo.setPasswordHash(passwordEncoder.encode(CONTRASENYA));
        nuevo.setRol(rol);
        nuevo.setActivo(true);
        Usuario guardado = usuarioRepository.save(nuevo);
        usuarios.add(guardado);
        return guardado;
    }

    private String entrar(Usuario usuario) throws Exception {
        HttpResponse<String> login = enviar(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + puerto + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email":"%s","password":"%s"}
                        """.formatted(usuario.getEmail(), CONTRASENYA), StandardCharsets.UTF_8)));
        if (login.statusCode() != 200) {
            throw new IllegalStateException("No se pudo entrar como " + usuario.getEmail() + ": " + login.body());
        }
        return JsonPath.read(login.body(), "$.token");
    }

    private HttpResponse<String> pedir(Listado listado, long pagina) throws Exception {
        return enviar(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + puerto + listado.ruta() + "?page=" + pagina))
                .header("Authorization", "Bearer " + listado.token())
                .GET());
    }

    private HttpResponse<String> enviar(HttpRequest.Builder peticion) throws Exception {
        return http.send(peticion.timeout(ESPERA).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
