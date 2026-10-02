package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.FotosDePrueba;
import com.rescateanimal.backend.ImagenesDePrueba;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.LimiteFotoDiarioRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.service.AlmacenFotosService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/fotos de extremo a extremo: servidor real, multipart real y
 * PostgreSQL real.
 *
 * Existe sobre todo por lo que MockMvc no puede probar: el límite de tamaño lo
 * aplica el resolver de multipart al leer la petición, antes de llegar al
 * controller, y solo una petición HTTP de verdad pasa por él.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d") y
 * perfil "test" activo, como el resto de pruebas de extremo a extremo.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FotoSubidaFlujoCompletoTest {

    private static final Duration ESPERA = Duration.ofSeconds(10);
    private static final String CLAVE = "passw0rd123";
    private static final ZoneId ZONA_CUPO = ZoneId.of("Europe/Madrid");

    @Value("${app.fotos.limite-diario}")
    private int limiteDiario;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int puerto;

    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private FotoRepository fotoRepository;
    @Autowired
    private LimiteFotoDiarioRepository limiteFotoDiarioRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private AlmacenFotosService almacen;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> fotosSubidas = new ArrayList<>();

    private final List<Long> usuarios = new ArrayList<>();
    private Long idUsuario;
    private String token;

    @BeforeEach
    void registrarYEntrar() throws Exception {
        token = nuevoUsuario();
        idUsuario = usuarios.get(0);
    }

    @AfterEach
    void limpiar() {
        fotosSubidas.forEach(almacen::borrar);
        fotoRepository.deleteAllById(fotosSubidas);
        // getUsuario().getId() no inicializa el proxy: es seguro desconectado.
        alertaRepository.deleteAll(alertaRepository.findAll().stream()
                .filter(a -> usuarios.contains(a.getUsuario().getId()))
                .toList());
        FotosDePrueba.borrarLasDe(usuarios, fotoRepository, limiteFotoDiarioRepository, almacen);
        usuarioRepository.deleteAllById(usuarios);
    }

    @Test
    @DisplayName("fotoUrl solo admite fotos subidas por el propio usuario: ni externas ni ajenas")
    void soloFotosPropias() throws Exception {
        String ajena = FotosDePrueba.subir(http, puerto, nuevoUsuario());
        String propia = FotosDePrueba.subir(http, puerto, token);

        for (String rechazada : List.of("https://cdn.test/luna.jpg", ajena)) {
            HttpResponse<String> respuesta = perfil(rechazada);
            assertThat(respuesta.statusCode()).as(rechazada).isEqualTo(400);
            assertThat((String) JsonPath.read(respuesta.body(), "$.codigo")).isEqualTo("VALIDACION");
            assertThat((String) JsonPath.read(respuesta.body(), "$.errores.fotoUrl")).isNotBlank();
        }

        HttpResponse<String> aceptada = perfil(propia);
        assertThat(aceptada.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(aceptada.body(), "$.fotoUrl")).isEqualTo(propia);
    }

    @Test
    @DisplayName("Agotado el cupo del día: 429 con el límite y la medianoche siguiente, sin archivo ni fila")
    void cupoDiario() throws Exception {
        fijarContadorDeHoy(limiteDiario - 1);

        assertThat(subir(ImagenesDePrueba.jpeg(8, 8), token).statusCode())
                .as("la última del cupo").isEqualTo(201);
        long fotosAntes = fotoRepository.count();

        HttpResponse<String> pasada = subir(ImagenesDePrueba.jpeg(8, 8), token);

        assertThat(pasada.statusCode()).isEqualTo(429);
        assertThat((String) JsonPath.read(pasada.body(), "$.codigo")).isEqualTo("LIMITE_FOTOS_ALCANZADO");
        assertThat((Integer) JsonPath.read(pasada.body(), "$.limiteDiario")).isEqualTo(limiteDiario);
        assertThat((String) JsonPath.read(pasada.body(), "$.reiniciaEn"))
                .startsWith(LocalDate.now(ZONA_CUPO).plusDays(1) + "T00:00");
        assertThat(fotoRepository.count()).isEqualTo(fotosAntes);
    }

    @Test
    @DisplayName("El cupo se reinicia al cambiar de día")
    void cupoSeReiniciaAlDiaSiguiente() throws Exception {
        fijarContadorDeHoy(limiteDiario);
        assertThat(subir(ImagenesDePrueba.jpeg(8, 8), token).statusCode()).isEqualTo(429);

        // Medianoche: el contador agotado pasa a ser el de ayer. Es exactamente
        // lo que ve la aplicación al cambiar de día, sin esperar a que cambie.
        jdbc.update("UPDATE limite_foto_diario SET fecha = fecha - 1 WHERE usuario_id = ?", idUsuario);

        assertThat(subir(ImagenesDePrueba.jpeg(8, 8), token).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("Una subida rechazada con 400 no gasta cupo")
    void rechazadaNoGastaCupo() throws Exception {
        fijarContadorDeHoy(limiteDiario - 1);

        assertThat(subir("no soy una imagen".getBytes(StandardCharsets.UTF_8), token).statusCode()).isEqualTo(400);

        assertThat(subir(ImagenesDePrueba.jpeg(8, 8), token).statusCode())
                .as("el 400 deshizo su consumo: aún queda la última del cupo")
                .isEqualTo(201);
    }

    /** Deja el contador de hoy del usuario en el valor pedido, exista o no la fila. */
    private void fijarContadorDeHoy(int valor) {
        jdbc.update("""
                INSERT INTO limite_foto_diario (usuario_id, fecha, contador) VALUES (?, ?, ?)
                ON CONFLICT (usuario_id, fecha) DO UPDATE SET contador = EXCLUDED.contador
                """, idUsuario, LocalDate.now(ZONA_CUPO), valor);
    }

    @Test
    @DisplayName("Cambiar la foto de perfil borra la anterior: fila, archivo y URL")
    void cambiarLaFotoDePerfil() throws Exception {
        String primera = FotosDePrueba.subir(http, puerto, token);
        String segunda = FotosDePrueba.subir(http, puerto, token);
        assertThat(perfil(primera).statusCode()).isEqualTo(200);

        assertThat(perfil(segunda).statusCode()).isEqualTo(200);

        assertBorrada(primera);
        assertThat(descargar(rutaDe(segunda)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("La baja borra la foto de perfil y conserva las fotos de sus alertas, aunque sea la misma")
    void laBajaBorraSoloLaFotoDePerfil() throws Exception {
        String deAlerta = FotosDePrueba.subir(http, puerto, token);
        String dePerfil = FotosDePrueba.subir(http, puerto, token);
        publicarAlerta(deAlerta);
        assertThat(perfil(dePerfil).statusCode()).isEqualTo(200);

        assertThat(darseDeBaja().statusCode()).isEqualTo(204);

        assertBorrada(dePerfil);
        assertThat(descargar(rutaDe(deAlerta)).statusCode())
                .as("la foto de su alerta es la de un animal: se conserva")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Si la foto de perfil también está en una alerta, la baja no la borra")
    void laFotoCompartidaSobreviveALaBaja() throws Exception {
        String compartida = FotosDePrueba.subir(http, puerto, token);
        publicarAlerta(compartida);
        assertThat(perfil(compartida).statusCode()).isEqualTo(200);

        assertThat(darseDeBaja().statusCode()).isEqualTo(204);

        assertThat(descargar(rutaDe(compartida)).statusCode()).isEqualTo(200);
        assertThat(fotoRepository.existsById(almacen.nombreDe(compartida).orElseThrow())).isTrue();
    }

    private void assertBorrada(String url) throws Exception {
        String nombre = almacen.nombreDe(url).orElseThrow();
        assertThat(fotoRepository.existsById(nombre)).as("fila").isFalse();
        assertThat(almacen.abrir(nombre)).as("archivo, borrado tras el commit").isEmpty();
        assertThat(descargar(rutaDe(url)).statusCode()).isEqualTo(404);
    }

    private static String rutaDe(String url) {
        return "/fotos/" + url.substring(url.lastIndexOf('/') + 1);
    }

    /**
     * Una alerta con especie y punto propios de esta prueba, para que la
     * detección de duplicados (RF-25) no choque con las de otras pruebas.
     */
    private void publicarAlerta(String fotoUrl) throws Exception {
        double desplazamiento = Math.random() / 10;
        HttpResponse<String> alerta = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + "/api/alertas"))
                        .timeout(ESPERA)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"descripcion":"Hurón con collar azul","especie":"%s","fotoUrl":"%s",
                                 "fechaAvistamiento":"%s",
                                 "ubicacion":{"latitud":%s,"longitud":%s}}
                                """.formatted("huron-" + UUID.randomUUID().toString().substring(0, 8), fotoUrl,
                                Instant.now().minusSeconds(3600), 40.3 + desplazamiento, -3.6 - desplazamiento),
                                StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(alerta.statusCode()).as(alerta.body()).isEqualTo(201);
    }

    private HttpResponse<String> darseDeBaja() throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + "/api/usuarios/me"))
                        .timeout(ESPERA)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .method("DELETE", HttpRequest.BodyPublishers.ofString("""
                                {"password":"%s"}
                                """.formatted(CLAVE), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> perfil(String fotoUrl) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + "/api/usuarios/me"))
                        .timeout(ESPERA)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"nombre":"Ana","fotoUrl":"%s"}
                                """.formatted(fotoUrl), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Registra un ciudadano nuevo, lo apunta para la limpieza y devuelve su token. */
    private String nuevoUsuario() throws Exception {
        String email = "fotos-" + UUID.randomUUID().toString().substring(0, 8) + "@test.es";
        HttpResponse<String> registro = json("/api/auth/registro/ciudadano", """
                {"nombre":"Ana","email":"%s","password":"%s","telefono":"600000000",
                 "areaGeografica":"%s"}
                """.formatted(email, CLAVE, "Fotos " + email));
        assertThat(registro.statusCode()).isEqualTo(201);
        usuarios.add(((Number) JsonPath.read(registro.body(), "$.id")).longValue());

        HttpResponse<String> login = json("/api/auth/login", """
                {"email":"%s","password":"%s"}
                """.formatted(email, CLAVE));
        return JsonPath.read(login.body(), "$.token");
    }

    @Test
    @DisplayName("Sube, registra quién la subió y guarda el archivo sin su EXIF")
    void subidaCompleta() throws Exception {
        byte[] conGps = ImagenesDePrueba.conExif(ImagenesDePrueba.jpeg(64, 48), "GPS-40.4168,-3.7038");

        HttpResponse<String> respuesta = subir(conGps, token);

        assertThat(respuesta.statusCode()).isEqualTo(201);
        String url = JsonPath.read(respuesta.body(), "$.url");
        assertThat(respuesta.headers().firstValue("Location")).contains(url);
        assertThat(url).startsWith("https://fotos.test/fotos/").endsWith(".jpg");

        String nombre = almacen.nombreDe(url).orElseThrow();
        fotosSubidas.add(nombre);

        assertThat(fotoRepository.existsByNombreAndUsuarioId(nombre, idUsuario)).isTrue();
        byte[] enDisco = Files.readAllBytes(almacen.abrir(nombre).orElseThrow());
        assertThat(ImagenesDePrueba.contiene(enDisco, "GPS-40.4168")).isFalse();
    }

    @Test
    @DisplayName("Por encima de 5 MB -> 413 FOTO_DEMASIADO_GRANDE, sin archivo ni fila")
    void demasiadoGrande() throws Exception {
        long fotosAntes = fotoRepository.count();

        HttpResponse<String> respuesta = subir(new byte[6 * 1024 * 1024], token);

        assertThat(respuesta.statusCode()).isEqualTo(413);
        assertThat((String) JsonPath.read(respuesta.body(), "$.codigo")).isEqualTo("FOTO_DEMASIADO_GRANDE");
        assertThat(fotoRepository.count()).isEqualTo(fotosAntes);
    }

    @Test
    @DisplayName("Sin token -> 401")
    void sinToken() throws Exception {
        assertThat(subir(ImagenesDePrueba.jpeg(8, 8), null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("GET /fotos/{nombre} sin token sirve la imagen con caché inmutable y nosniff")
    void servirSinToken() throws Exception {
        String url = JsonPath.read(subir(ImagenesDePrueba.jpeg(32, 24), token).body(), "$.url");
        String nombre = almacen.nombreDe(url).orElseThrow();
        fotosSubidas.add(nombre);

        HttpResponse<byte[]> respuesta = descargar("/fotos/" + nombre);

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(respuesta.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(respuesta.headers().firstValue("Cache-Control")).hasValueSatisfying(cache ->
                assertThat(cache).contains("max-age=31536000", "public", "immutable").doesNotContain("no-store"));
        assertThat(respuesta.body()).isEqualTo(Files.readAllBytes(almacen.abrir(nombre).orElseThrow()));
        assertThat(ImagenesDePrueba.leer(respuesta.body()).getWidth()).isEqualTo(32);
    }

    @Test
    @DisplayName("Un archivo en disco sin su fila no se sirve")
    void archivoSinFila() throws Exception {
        String suelto = almacen.guardar(ImagenesDePrueba.jpeg(4, 4), "jpg");
        fotosSubidas.add(suelto);

        assertThat(descargar("/fotos/" + suelto).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Ninguna ruta construida fuera del patrón sale de la carpeta de fotos")
    void recorrerRutas() throws Exception {
        for (String ruta : List.of(
                "/fotos/3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg",
                "/fotos/..%2F..%2Fpom.xml",
                "/fotos/..%5C..%5Cpom.xml",
                "/fotos/%2e%2e/%2e%2e/pom.xml",
                "/fotos/application.properties")) {
            HttpResponse<byte[]> respuesta = descargar(ruta);
            assertThat(respuesta.statusCode()).as(ruta).isIn(400, 404);
            assertThat(new String(respuesta.body(), StandardCharsets.UTF_8)).as(ruta).doesNotContain("<project");
        }
    }

    @Test
    @DisplayName("Sin token solo se puede leer: subir por /fotos no existe")
    void soloLectura() throws Exception {
        HttpResponse<String> respuesta = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + "/fotos/x.jpg"))
                        .timeout(ESPERA)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(respuesta.statusCode()).isEqualTo(401);
    }

    private HttpResponse<byte[]> descargar(String ruta) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + ruta))
                        .timeout(ESPERA)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> subir(byte[] contenido, String token) throws Exception {
        String frontera = "----frontera" + UUID.randomUUID();
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.writeBytes(("--" + frontera + "\r\n"
                + "Content-Disposition: form-data; name=\"archivo\"; filename=\"foto.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        cuerpo.writeBytes(contenido);
        cuerpo.writeBytes(("\r\n--" + frontera + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder peticion = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + puerto + "/api/fotos"))
                .timeout(ESPERA)
                .header("Content-Type", "multipart/form-data; boundary=" + frontera)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()));
        if (token != null) {
            peticion.header("Authorization", "Bearer " + token);
        }
        return http.send(peticion.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> json(String ruta, String cuerpo) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + ruta))
                        .timeout(ESPERA)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
