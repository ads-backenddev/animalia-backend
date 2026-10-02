package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.FotosDePrueba;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.LimiteFotoDiarioRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.ReporteRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.repository.ValoracionRepository;
import com.rescateanimal.backend.service.AlmacenFotosService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorrido completo de valoraciones y reportes contra el servidor y PostgreSQL
 * reales (RF-23, RF-26).
 *
 * EXISTE POR TRES COSAS QUE NINGÚN MOCK PUEDE DEMOSTRAR:
 *
 * 1. QUE LA MIGRACIÓN V9 SE APLICA Y HACE LO QUE DICE. Es la primera vez que se
 *    ejecuta contra ddl-auto=validate, y trae dos cambios que ningún test
 *    unitario alcanza: la restricción UNIQUE(usuario_id, protectora_id) y la
 *    desaparición de PROTECTORA.valoracion_media. Si la columna siguiera en la
 *    tabla y no en la entidad, o al revés, el contexto ni arrancaría.
 *
 * 2. QUE LA MEDIA SE CALCULA CON EL AVG DE VERDAD. En ValoracionServiceTest el
 *    AVG lo devuelve un mock, así que allí solo se comprueba el redondeo. Aquí
 *    las valoraciones se insertan por HTTP y la media la calcula PostgreSQL.
 *
 * 3. QUE LA CADENA DE RF-26 SE CIERRA. Reportar, aparecer en la cola del
 *    administrador y salir de ella al resolver son tres endpoints de dos
 *    controllers distintos, con un rol distinto cada uno.
 *
 * EL ESCENARIO ES EL MÍNIMO QUE PERMITE VALORAR: una protectora activa con un
 * animal publicado, y un ciudadano que desliza a favor sobre él. Ese
 * deslizamiento es el "contacto" que RF-23 exige, y montarlo entero es
 * precisamente lo que hace que la prueba signifique algo.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d") y
 * perfil "test" activo, como AdminFlujoCompletoTest.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValoracionesReportesFlujoCompletoTest {

    private static final Duration ESPERA = Duration.ofSeconds(10);
    private static final String CLAVE = "passw0rd123";

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
    private ProtectoraRepository protectoraRepository;
    @Autowired
    private AnimalAdopcionRepository animalAdopcionRepository;
    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private MatchRegistroRepository matchRegistroRepository;
    @Autowired
    private ValoracionRepository valoracionRepository;
    @Autowired
    private ReporteRepository reporteRepository;
    @Autowired
    private NotificacionRepository notificacionRepository;
    @Autowired
    private LimiteMatchDiarioRepository limiteMatchDiarioRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String sufijo;
    private String tokenAdmin;
    private String tokenCiudadano;
    private String tokenOtroCiudadano;
    private String tokenProtectora;
    private Long idCiudadano;
    private Long idOtroCiudadano;
    private Long idCuentaProtectora;
    private Long idProtectora;
    private Long idAnimal;

    private final List<Long> usuariosCreados = new ArrayList<>();

    @BeforeEach
    void prepararEscenario() throws Exception {
        sufijo = UUID.randomUUID().toString().substring(0, 8);

        crearAdministrador();
        tokenAdmin = entrar(emailDe("admin"));

        idCiudadano = registrarCiudadano("ciudadano", "Ana");
        tokenCiudadano = entrar(emailDe("ciudadano"));

        idOtroCiudadano = registrarCiudadano("otro", "Luis");
        tokenOtroCiudadano = entrar(emailDe("otro"));

        registrarYAprobarProtectora();
        tokenProtectora = entrar(emailDe("protectora"));

        idAnimal = publicarAnimal();
    }

    @AfterEach
    void limpiar() {
        valoracionRepository.deleteAll(valoracionRepository.findAll().stream()
                .filter(v -> v.getProtectora().getId().equals(idProtectora))
                .toList());
        reporteRepository.deleteAll(reporteRepository.findAll().stream()
                .filter(r -> usuariosCreados.contains(r.getUsuario().getId()))
                .toList());
        // En orden de dependencia: el chat apunta al match, y el match al usuario.
        // Un swipe a favor crea los dos, así que borrar el match primero choca
        // contra la clave ajena del chat.
        List<Long> matchesPropios = matchRegistroRepository.findAll().stream()
                .filter(m -> usuariosCreados.contains(m.getUsuario().getId()))
                .map(m -> m.getId())
                .toList();
        chatRepository.deleteAll(chatRepository.findByMatchIdIn(matchesPropios));
        matchRegistroRepository.deleteAllById(matchesPropios);
        notificacionRepository.deleteAll(notificacionRepository.findAll().stream()
                .filter(n -> usuariosCreados.contains(n.getUsuario().getId()))
                .toList());
        limiteMatchDiarioRepository.deleteAll(limiteMatchDiarioRepository.findAll().stream()
                .filter(l -> usuariosCreados.contains(l.getUsuario().getId()))
                .toList());
        if (idAnimal != null) {
            animalAdopcionRepository.deleteById(idAnimal);
        }
        if (idProtectora != null) {
            protectoraRepository.deleteById(idProtectora);
        }
        FotosDePrueba.borrarLasDe(usuariosCreados, fotoRepository, limiteFotoDiarioRepository, almacenFotos);
        usuarioRepository.deleteAllById(usuariosCreados);
    }

    // ---------------------------------------------------------------------
    // RF-23
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Sin haber contactado no se puede valorar, y tras contactar sí")
    void valorarExigeContactoPrevio() throws Exception {
        // 1. Recién registrado, este ciudadano no ha tratado con la protectora.
        HttpResponse<String> sinContacto = valorar(tokenCiudadano, 5);
        assertThat(sinContacto.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(sinContacto.body(), "$.codigo"))
                .isEqualTo("SIN_INTERACCION_PREVIA");

        // Y no ha quedado ninguna fila: el rechazo es antes de escribir.
        assertThat(valoracionRepository.countByProtectoraId(idProtectora)).isZero();

        // 2. Desliza a favor sobre un animal de esa protectora. Eso es el
        //    "contacto" que RF-23 pide, y abre el chat con ella (RF-12).
        deslizarAFavor(tokenCiudadano);

        // 3. Ahora sí puede valorar.
        HttpResponse<String> conContacto = valorar(tokenCiudadano, 5);
        assertThat(conContacto.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(conContacto.body(), "$.puntuacion")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(conContacto.body(), "$.autor.id"))
                .isEqualTo(idCiudadano.intValue());
    }

    @Test
    @DisplayName("Valorar dos veces actualiza la nota y no deja dos filas")
    void valorarDosVecesActualiza() throws Exception {
        deslizarAFavor(tokenCiudadano);

        assertThat(valorar(tokenCiudadano, 2).statusCode()).isEqualTo(200);
        HttpResponse<String> segunda = valorar(tokenCiudadano, 5);

        assertThat(segunda.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(segunda.body(), "$.puntuacion")).isEqualTo(5);

        // LO IMPORTANTE ES ESTO: una sola fila. Sin la comprobación previa del
        // service habría dos, y la media saldría inflada por un usuario que ha
        // votado dos veces.
        assertThat(valoracionRepository.countByProtectoraId(idProtectora)).isEqualTo(1);
        assertThat(valoracionRepository.mediaDeProtectora(idProtectora)).isEqualTo(5.0);
    }

    @Test
    @DisplayName("La media la calcula PostgreSQL sobre las valoraciones reales, y se recalcula al cambiar una")
    void laMediaSeRecalcula() throws Exception {
        deslizarAFavor(tokenCiudadano);
        deslizarAFavor(tokenOtroCiudadano);

        // Dos valoraciones: 5 y 2 -> 3,50.
        assertThat(valorar(tokenCiudadano, 5).statusCode()).isEqualTo(200);
        assertThat(valorar(tokenOtroCiudadano, 2).statusCode()).isEqualTo(200);
        assertThat(mediaSegunElDetalleDelAnimal()).isEqualTo(3.5);
        assertThat(totalSegunElDetalleDelAnimal()).isEqualTo(2);

        // El segundo cambia de opinión: 5 y 4 -> 4,50. Si la media estuviera
        // guardada en una columna, aquí seguiría diciendo 3,50.
        assertThat(valorar(tokenOtroCiudadano, 4).statusCode()).isEqualTo(200);
        assertThat(mediaSegunElDetalleDelAnimal()).isEqualTo(4.5);
        assertThat(totalSegunElDetalleDelAnimal()).isEqualTo(2);

        // Y el panel de la protectora ve exactamente lo mismo, calculado por su
        // cuenta desde el otro lado de la aplicación.
        HttpResponse<String> panel =
                enviar("GET", "/api/protectoras/panel/valoraciones", tokenProtectora, null);
        assertThat(panel.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Double>read(panel.body(), "$.media")).isEqualTo(4.5);
        assertThat(JsonPath.<Integer>read(panel.body(), "$.total")).isEqualTo(2);
        assertThat(JsonPath.<List<Integer>>read(panel.body(), "$.valoraciones[*].puntuacion"))
                .containsExactlyInAnyOrder(5, 4);
    }

    @Test
    @DisplayName("Sin ninguna valoración la media se omite, no es cero")
    void sinValoracionesNoHayMedia() throws Exception {
        HttpResponse<String> detalle =
                enviar("GET", "/api/adopcion/" + idAnimal, tokenCiudadano, null);

        assertThat(detalle.statusCode()).isEqualTo(200);
        assertThat(detalle.body()).doesNotContain("protectoraValoracionMedia");
        assertThat(JsonPath.<Integer>read(detalle.body(), "$.protectoraValoracionTotal")).isZero();
    }

    @Test
    @DisplayName("Valorar una protectora que no existe da 404, no un 403 engañoso")
    void valorarLoInexistenteDa404() throws Exception {
        assertThat(enviar("PUT", "/api/protectoras/99999999/valoracion", tokenCiudadano, """
                {"puntuacion":4}
                """).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("La puntuación fuera de 1..5 se rechaza con 400 antes de tocar nada")
    void laPuntuacionFueraDeRangoDa400() throws Exception {
        deslizarAFavor(tokenCiudadano);

        assertThat(valorar(tokenCiudadano, 0).statusCode()).isEqualTo(400);
        assertThat(valorar(tokenCiudadano, 6).statusCode()).isEqualTo(400);
        assertThat(valoracionRepository.countByProtectoraId(idProtectora)).isZero();
    }

    // ---------------------------------------------------------------------
    // RF-26
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un reporte llega a la cola del administrador y sale de ella al resolverlo")
    void elReporteRecorreSuCicloCompleto() throws Exception {
        // 1. Un ciudadano reporta el animal publicado.
        HttpResponse<String> creado = enviar("POST", "/api/reportes", tokenCiudadano, """
                {"tipoObjetivo":"PUBLICACION","objetivoId":%d,
                 "tipoContenido":"ADOPCION","motivo":"INFORMACION_FALSA"}
                """.formatted(idAnimal));
        assertThat(creado.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(creado.body(), "$.estado")).isEqualTo("PENDIENTE");
        long idReporte = numero(creado.body(), "$.id");

        // 2. Aparece en la cola del administrador, con el par (tipo, id) que
        //    DELETE /api/admin/publicaciones espera recibir.
        HttpResponse<String> cola = enviar("GET", "/api/admin/reportes", tokenAdmin, null);
        assertThat(cola.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(cola.body(), "$[*].id"))
                .contains((int) idReporte);

        // 3. Y NO enseña nada de la persona reportada: solo el par.
        assertThat(cola.body()).doesNotContain(emailDe("protectora"));

        // 4. El administrador lo resuelve. Sale de la cola y avisa a quien lo emitió.
        HttpResponse<String> resuelto = enviar("PUT",
                "/api/admin/reportes/" + idReporte + "/resolver", tokenAdmin, null);
        assertThat(resuelto.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(resuelto.body(), "$.estado")).isEqualTo("REVISADO");

        assertThat(JsonPath.<List<Integer>>read(
                enviar("GET", "/api/admin/reportes", tokenAdmin, null).body(), "$[*].id"))
                .doesNotContain((int) idReporte);

        // El aviso va a quien reportó, no a quien fue reportado.
        assertThat(tiposNotificadosA(idCiudadano))
                .containsExactly(TipoNotificacion.REPORTE_RESUELTO);
        assertThat(tiposNotificadosA(idCuentaProtectora))
                .as("al reportado no se le avisa de que alguien lo denunció")
                .doesNotContain(TipoNotificacion.REPORTE_RESUELTO);

        // 5. Resolver dos veces no cambia nada ni vuelve a notificar.
        assertThat(enviar("PUT", "/api/admin/reportes/" + idReporte + "/resolver",
                tokenAdmin, null).statusCode()).isEqualTo(200);
        assertThat(tiposNotificadosA(idCiudadano)).hasSize(1);
    }

    @Test
    @DisplayName("Reportar una publicación inexistente da 404 y no llena la cola")
    void reportarLoInexistenteDa404() throws Exception {
        HttpResponse<String> respuesta = enviar("POST", "/api/reportes", tokenCiudadano, """
                {"tipoObjetivo":"PUBLICACION","objetivoId":99999999,
                 "tipoContenido":"ADOPCION","motivo":"SPAM"}
                """);

        assertThat(respuesta.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<List<Integer>>read(
                enviar("GET", "/api/admin/reportes", tokenAdmin, null).body(), "$[*].id"))
                .isEmpty();
    }

    @Test
    @DisplayName("El tipo importa: el mismo id como ALERTA no existe aunque exista como ADOPCION")
    void elTipoDiscriminaDeVerdad() throws Exception {
        // El id del animal existe en ANIMAL_ADOPCION. Pedirlo como ALERTA tiene
        // que dar 404: es lo que justifica que TipoContenido acompañe al id.
        assertThat(enviar("POST", "/api/reportes", tokenCiudadano, """
                {"tipoObjetivo":"PUBLICACION","objetivoId":%d,
                 "tipoContenido":"ALERTA","motivo":"SPAM"}
                """.formatted(idAnimal)).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Sin rol ADMIN no se lee ni se resuelve la cola de reportes")
    void laColaDeReportesEsSoloDelAdmin() throws Exception {
        assertThat(enviar("GET", "/api/admin/reportes", tokenCiudadano, null).statusCode())
                .isEqualTo(403);
        assertThat(enviar("GET", "/api/admin/reportes", tokenProtectora, null).statusCode())
                .isEqualTo(403);
        assertThat(enviar("PUT", "/api/admin/reportes/1/resolver", tokenCiudadano, null)
                .statusCode()).isEqualTo(403);

        // Y sin token, 401: no se llega ni a mirar el rol.
        assertThat(enviar("GET", "/api/admin/reportes", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Cualquier autenticado puede reportar: la barrera es mínima a propósito")
    void reportarNoExigeRol() throws Exception {
        assertThat(enviar("POST", "/api/reportes", tokenProtectora, """
                {"tipoObjetivo":"USUARIO","objetivoId":%d,"motivo":"ACOSO"}
                """.formatted(idCiudadano)).statusCode()).isEqualTo(201);
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private HttpResponse<String> valorar(String token, int puntuacion) throws Exception {
        return enviar("PUT", "/api/protectoras/" + idProtectora + "/valoracion", token,
                "{\"puntuacion\":" + puntuacion + "}");
    }

    /** El swipe a favor sobre el animal: es el "contacto" que RF-23 exige. */
    private void deslizarAFavor(String token) throws Exception {
        HttpResponse<String> swipe = enviar("POST", "/api/matches", token, """
                {"publicacionId":%d,"tipoPublicacion":"ADOPCION","interesado":true}
                """.formatted(idAnimal));
        assertThat(swipe.statusCode())
                .as("el swipe tiene que salir bien: sin él no hay contacto que valorar")
                .isEqualTo(201);
    }

    private Double mediaSegunElDetalleDelAnimal() throws Exception {
        HttpResponse<String> detalle =
                enviar("GET", "/api/adopcion/" + idAnimal, tokenCiudadano, null);
        assertThat(detalle.statusCode()).isEqualTo(200);
        return ((Number) JsonPath.read(detalle.body(), "$.protectoraValoracionMedia")).doubleValue();
    }

    private int totalSegunElDetalleDelAnimal() throws Exception {
        HttpResponse<String> detalle =
                enviar("GET", "/api/adopcion/" + idAnimal, tokenCiudadano, null);
        return JsonPath.read(detalle.body(), "$.protectoraValoracionTotal");
    }

    private List<TipoNotificacion> tiposNotificadosA(Long usuarioId) {
        return notificacionRepository.findAll().stream()
                .filter(n -> n.getUsuario().getId().equals(usuarioId))
                .map(Notificacion::getTipo)
                .toList();
    }

    private String emailDe(String papel) {
        return papel + "-" + sufijo + "@test.es";
    }

    private static String credenciales(String email) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, CLAVE);
    }

    private void crearAdministrador() {
        Usuario admin = new Usuario();
        admin.setNombre("Administrador");
        admin.setEmail(emailDe("admin"));
        admin.setPasswordHash(passwordEncoder.encode(CLAVE));
        admin.setRol(Rol.ADMIN);
        admin.setActivo(true);

        usuariosCreados.add(usuarioRepository.save(admin).getId());
    }

    private Long registrarCiudadano(String papel, String nombre) throws Exception {
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"%s","email":"%s","password":"%s"}
                """.formatted(nombre, emailDe(papel), CLAVE));
        assertThat(registro.statusCode()).isEqualTo(201);

        Long id = numero(registro.body(), "$.id");
        usuariosCreados.add(id);
        return id;
    }

    private void registrarYAprobarProtectora() throws Exception {
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/protectora", null, """
                {"nombre":"Refugio","email":"%s","password":"%s",
                 "nombreOrganizacion":"Huellas %s","direccion":"C/ Mayor 1",
                 "zonaGeografica":"Madrid"}
                """.formatted(emailDe("protectora"), CLAVE, sufijo));
        assertThat(registro.statusCode()).isEqualTo(201);

        idCuentaProtectora = usuarioRepository.findByEmail(emailDe("protectora"))
                .orElseThrow(() -> new IllegalStateException("la cuenta no se ha guardado"))
                .getId();
        usuariosCreados.add(idCuentaProtectora);

        idProtectora = protectoraRepository.findByUsuarioId(idCuentaProtectora)
                .orElseThrow(() -> new IllegalStateException("la protectora no se ha guardado"))
                .getId();

        // Sin aprobar no puede ni entrar, y hace falta que publique un animal.
        assertThat(enviar("PUT", "/api/admin/protectoras/" + idProtectora + "/aprobar",
                tokenAdmin, null).statusCode()).isEqualTo(200);
    }

    private Long publicarAnimal() throws Exception {
        HttpResponse<String> alta = enviar("POST", "/api/adopcion", tokenProtectora, """
                {"nombre":"Luna","especie":"perro","tamanyo":"MEDIANO",
                 "descripcion":"Cariñosa y tranquila, busca familia",
                 "fotoUrl":"%s","zonaGeografica":"Madrid"}
                """.formatted(FotosDePrueba.subir(http, puerto, tokenProtectora)));
        assertThat(alta.statusCode()).isEqualTo(201);
        return numero(alta.body(), "$.id");
    }

    private String entrar(String email) throws Exception {
        HttpResponse<String> login = enviar("POST", "/api/auth/login", null, credenciales(email));
        assertThat(login.statusCode()).isEqualTo(200);
        return JsonPath.read(login.body(), "$.token");
    }

    private static Long numero(String json, String ruta) {
        return ((Number) JsonPath.read(json, ruta)).longValue();
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
