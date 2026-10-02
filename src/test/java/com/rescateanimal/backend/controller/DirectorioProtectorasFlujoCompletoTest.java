package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.FotosDePrueba;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
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
 * Recorrido completo del directorio de protectoras contra el servidor y
 * PostgreSQL reales (RF-06).
 *
 * EXISTE POR CUATRO COSAS QUE NINGÚN MOCK PUEDE DEMOSTRAR:
 *
 * 1. QUE EL FILTRO DE ZONA NO REVIENTA CONTRA POSTGRESQL. Es la trampa que ya
 *    costó un fallo real en el catálogo de adopción: un parámetro nulo no tiene
 *    tipo, PostgreSQL lo toma por bytea y {@code lower(:zona)} muere con
 *    "function lower(bytea) does not exist". En un test con mocks la consulta ni
 *    se ejecuta, así que el error solo aparece aquí. Se piden las dos formas: con
 *    filtro y sin él.
 *
 * 2. QUE LA PÁGINA CUENTA BIEN. El listado lleva un JOIN FETCH, que no puede
 *    viajar en una consulta de recuento, así que el countQuery está escrito
 *    aparte y podría decir algo distinto que la consulta principal sin que nada
 *    lo delatara. Aquí se comparan el total declarado y las filas devueltas.
 *
 * 3. QUE LA NOTA ES LA MISMA EN LOS DOS SITIOS DONDE SE ENSEÑA. RF-06 y RF-07
 *    publican la reputación de la misma protectora, y las dos medias las calcula
 *    PostgreSQL sobre valoraciones reales insertadas por HTTP. Que coincidan es
 *    lo que garantiza que el ciudadano no vea dos números distintos.
 *
 * 4. QUE UNA PROTECTORA DADA DE BAJA O BLOQUEADA DESAPARECE DE VERDAD. Su fila
 *    de PROTECTORA conserva estado ACTIVA —la baja de RF-19 no lo toca— así que
 *    lo que la saca del directorio es la condición sobre USUARIO.activo, y eso
 *    solo se comprueba dándola de baja de verdad y volviendo a pedir el listado.
 *
 * EL ESCENARIO SON CUATRO PROTECTORAS EN LA MISMA ZONA INVENTADA, una por cada
 * caso que hay que distinguir: activa entidad, activa persona física, pendiente y
 * rechazada. La zona lleva el sufijo aleatorio de la ejecución, así que el filtro
 * devuelve exactamente las de esta prueba y las asserts pueden hablar de totales
 * sin que las de otras ejecuciones estorben.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d") y
 * perfil "test" activo, como el resto de recorridos completos.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectorioProtectorasFlujoCompletoTest {

    private static final Duration ESPERA = Duration.ofSeconds(10);
    private static final String CLAVE = "passw0rd123";

    @LocalServerPort
    private int puerto;

    @Autowired
    private FotoRepository fotoRepository;
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
    private NotificacionRepository notificacionRepository;
    @Autowired
    private LimiteMatchDiarioRepository limiteMatchDiarioRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private String sufijo;
    private String zona;
    private String tokenAdmin;
    private String tokenCiudadano;
    private String tokenActiva;
    private Long idCiudadano;
    private Long idCuentaActiva;
    private Long idActiva;
    private Long idParticular;
    private Long idPendiente;
    private Long idRechazada;
    private Long idAnimal;

    private final List<Long> usuariosCreados = new ArrayList<>();
    private final List<Long> protectorasCreadas = new ArrayList<>();
    private final List<Long> animalesCreados = new ArrayList<>();

    @BeforeEach
    void prepararEscenario() throws Exception {
        sufijo = UUID.randomUUID().toString().substring(0, 8);
        zona = "Zona-" + sufijo;

        crearAdministrador();
        tokenAdmin = entrar(emailDe("admin"));

        idCiudadano = registrarCiudadano();
        tokenCiudadano = entrar(emailDe("ciudadano"));

        idActiva = registrarProtectora("activa", "Huellas " + sufijo, false);
        idCuentaActiva = cuentaDe("activa");
        aprobar(idActiva);
        tokenActiva = entrar(emailDe("activa"));

        idParticular = registrarProtectora("particular", "Refugio de Marta " + sufijo, true);
        aprobar(idParticular);

        idPendiente = registrarProtectora("pendiente", "En espera " + sufijo, false);

        idRechazada = registrarProtectora("rechazada", "Denegada " + sufijo, false);
        rechazar(idRechazada);

        idAnimal = publicarAnimal(tokenActiva, "Luna");
        animalesCreados.add(idAnimal);
    }

    @AfterEach
    void limpiar() {
        valoracionRepository.deleteAll(valoracionRepository.findAll().stream()
                .filter(v -> protectorasCreadas.contains(v.getProtectora().getId()))
                .toList());
        // En orden de dependencia: el chat apunta al match, y el match al usuario.
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
        animalAdopcionRepository.deleteAllById(animalesCreados);
        protectoraRepository.deleteAllById(protectorasCreadas);
        FotosDePrueba.borrarLasDe(usuariosCreados, fotoRepository, almacenFotos);
        usuarioRepository.deleteAllById(usuariosCreados);
    }

    // ---------------------------------------------------------------------
    // Quién se publica
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("El listado enseña las ACTIVA y deja fuera a las PENDIENTE y RECHAZADA")
    void elListadoSoloEnsenaLasActivas() throws Exception {
        HttpResponse<String> listado = directorio(zona);

        assertThat(listado.statusCode()).isEqualTo(200);
        assertThat(idsDelListado(listado)).containsExactlyInAnyOrder(idActiva, idParticular);

        // Y no se cuela ni el nombre de las otras dos por ninguna parte.
        assertThat(listado.body()).doesNotContain("En espera " + sufijo);
        assertThat(listado.body()).doesNotContain("Denegada " + sufijo);
    }

    /**
     * El total declarado y las filas devueltas salen de dos consultas distintas
     * —el countQuery del JOIN FETCH está escrito aparte— y podrían decir cosas
     * distintas sin que nada avisara.
     */
    @Test
    @DisplayName("El total de la página coincide con lo que devuelve, contando aparte")
    void elTotalCoincideConLoDevuelto() throws Exception {
        HttpResponse<String> listado = directorio(zona);

        int total = JsonPath.read(listado.body(), "$.totalElements");
        assertThat(total).isEqualTo(2);
        assertThat(idsDelListado(listado)).hasSize(total);
        assertThat(JsonPath.<Boolean>read(listado.body(), "$.last")).isTrue();
    }

    @Test
    @DisplayName("La ficha de una PENDIENTE da 404, también pidiéndola por su id")
    void laFichaDeUnaPendienteDa404() throws Exception {
        assertThat(ficha(idPendiente).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("La de una RECHAZADA también")
    void laFichaDeUnaRechazadaDa404() throws Exception {
        assertThat(ficha(idRechazada).statusCode()).isEqualTo(404);
    }

    /**
     * Un 403 confirmaría que la fila está ahí. Las dos respuestas tienen que ser
     * indistinguibles, mensaje incluido salvo por el id.
     */
    @Test
    @DisplayName("El 404 de una pendiente no se distingue del de una protectora inexistente")
    void losDosCuatrocientosCuatroSonElMismo() throws Exception {
        HttpResponse<String> pendiente = ficha(idPendiente);
        HttpResponse<String> inexistente = ficha(99999999L);

        assertThat(pendiente.statusCode()).isEqualTo(inexistente.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(pendiente.body(), "$.codigo"))
                .isEqualTo(JsonPath.read(inexistente.body(), "$.codigo"))
                .isEqualTo("NO_ENCONTRADO");
    }

    @Test
    @DisplayName("La ficha de una ACTIVA sí responde, con sus animales disponibles")
    void laFichaDeUnaActivaResponde() throws Exception {
        HttpResponse<String> respuesta = ficha(idActiva);

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(respuesta.body(), "$.nombreOrganizacion"))
                .isEqualTo("Huellas " + sufijo);
        assertThat(JsonPath.<List<Integer>>read(respuesta.body(), "$.animales.content[*].id"))
                .containsExactly(idAnimal.intValue());
    }

    /**
     * LA CONDICIÓN QUE EL ESTADO NO CUBRE. La baja de RF-19 anonimiza al usuario y
     * retira sus animales, pero deja la fila de PROTECTORA en ACTIVA: sin mirar
     * usuario.activo, el directorio seguiría publicando su ficha y ofreciendo como
     * contacto el email anonimizado "@cuenta-eliminada.invalid".
     */
    @Test
    @DisplayName("Una protectora que se da de baja desaparece del directorio y de su ficha")
    void laBajaLaSacaDelDirectorio() throws Exception {
        assertThat(idsDelListado(directorio(zona))).contains(idActiva);

        HttpResponse<String> baja = enviar("DELETE", "/api/usuarios/me", tokenActiva, """
                {"password":"%s"}
                """.formatted(CLAVE));
        assertThat(baja.statusCode()).isEqualTo(204);

        assertThat(idsDelListado(directorio(zona))).doesNotContain(idActiva);
        assertThat(ficha(idActiva).statusCode()).isEqualTo(404);

        // Y su estado sigue siendo ACTIVA: lo que la saca es la cuenta, no el estado.
        assertThat(protectoraRepository.findById(idActiva).orElseThrow().getEstado().name())
                .isEqualTo("ACTIVA");
    }

    @Test
    @DisplayName("Una protectora bloqueada por el administrador tampoco aparece")
    void elBloqueoLaSacaDelDirectorio() throws Exception {
        HttpResponse<String> bloqueo = enviar("PUT",
                "/api/admin/usuarios/" + idCuentaActiva + "/bloquear", tokenAdmin, """
                        {"motivo":"Prueba de directorio"}
                        """);
        assertThat(bloqueo.statusCode()).isEqualTo(200);

        assertThat(idsDelListado(directorio(zona))).doesNotContain(idActiva);
        assertThat(ficha(idActiva).statusCode()).isEqualTo(404);
    }

    /**
     * REGRESIÓN DEL HALLAZGO DE LA REVISIÓN DE ESTE BLOQUE. Sacar a una
     * protectora del directorio no la sacaba del catálogo: rechazarla solo
     * cambiaba su estado y bloquearla solo ponía activo = false, y el catálogo
     * nunca ha mirado ninguna de las dos cosas. Sus animales seguían DISPONIBLE
     * en el escaparate y en el feed, y un ciudadano podía deslizar a favor y
     * abrir un chat con una cuenta que ya no puede entrar.
     *
     * Se comprobó ejecutándolo antes del arreglo: todas las aserciones contrarias
     * a estas pasaban. Esta prueba es lo que impide que vuelva.
     */
    @Test
    @DisplayName("Rechazar a una protectora le retira además el catálogo, no solo la ficha")
    void elRechazoLeRetiraElCatalogo() throws Exception {
        assertThat(idsDelCatalogo()).contains(idAnimal);

        assertThat(enviar("PUT", "/api/admin/protectoras/" + idActiva + "/rechazar",
                tokenAdmin, """
                        {"motivo":"Denuncias confirmadas"}
                        """).statusCode()).isEqualTo(200);

        assertThat(idsDelCatalogo())
                .as("un animal sin nadie que responda no puede seguir ofreciéndose")
                .doesNotContain(idAnimal);
        assertThat(estadoDelAnimal(tokenCiudadano)).isEqualTo("RETIRADO");

        // Y tampoco en el feed de swipe, que es por donde se llegaba al chat.
        assertThat(JsonPath.<List<Integer>>read(
                enviar("GET", "/api/adopcion/feed?zona=" + zona, tokenCiudadano, null).body(),
                "$[*].id")).doesNotContain(idAnimal.intValue());
    }

    @Test
    @DisplayName("Bloquear la cuenta de una protectora hace lo mismo")
    void elBloqueoLeRetiraElCatalogo() throws Exception {
        assertThat(enviar("PUT", "/api/admin/usuarios/" + idCuentaActiva + "/bloquear",
                tokenAdmin, """
                        {"motivo":"Denuncias confirmadas"}
                        """).statusCode()).isEqualTo(200);

        assertThat(idsDelCatalogo()).doesNotContain(idAnimal);
        assertThat(estadoDelAnimal(tokenCiudadano)).isEqualTo("RETIRADO");
    }

    @Test
    @DisplayName("Bloquear a un ciudadano no toca ningún catálogo")
    void elBloqueoDeUnCiudadanoNoTocaElCatalogo() throws Exception {
        assertThat(enviar("PUT", "/api/admin/usuarios/" + idCiudadano + "/bloquear",
                tokenAdmin, """
                        {"motivo":"Insultos reiterados"}
                        """).statusCode()).isEqualTo(200);

        assertThat(estadoDelAnimal(tokenAdmin)).isEqualTo("DISPONIBLE");
    }

    // ---------------------------------------------------------------------
    // Qué contacto se publica
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("De una entidad se publican email y teléfono, que es lo que RF-06 pide")
    void deUnaEntidadSePublicaElContacto() throws Exception {
        HttpResponse<String> respuesta = ficha(idActiva);

        assertThat(JsonPath.<String>read(respuesta.body(), "$.contacto.email"))
                .isEqualTo(emailDe("activa"));
        assertThat(JsonPath.<String>read(respuesta.body(), "$.contacto.telefono"))
                .isEqualTo("600111222");
        assertThat(JsonPath.<String>read(respuesta.body(), "$.direccion"))
                .isEqualTo("C/ Mayor 1");
        assertThat(JsonPath.<Boolean>read(respuesta.body(), "$.esPersonaFisica")).isFalse();
    }

    /**
     * El caso de V12: detrás de la protectora hay un particular, y su teléfono y
     * su dirección son su móvil y su domicilio. El email sí sale, porque sin él
     * RF-06 no se cumple para toda una clase de protectoras.
     */
    @Test
    @DisplayName("De un particular se publica el email, pero no su teléfono ni su domicilio")
    void deUnParticularNoSePublicaTelefonoNiDireccion() throws Exception {
        HttpResponse<String> respuesta = ficha(idParticular);

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(respuesta.body(), "$.esPersonaFisica")).isTrue();
        assertThat(JsonPath.<String>read(respuesta.body(), "$.contacto.email"))
                .isEqualTo(emailDe("particular"));

        // Omitidos, no nulos: mandarlos como null delataría que existen.
        assertThat(respuesta.body()).doesNotContain("600111222");
        assertThat(respuesta.body()).doesNotContain("C/ Mayor 1");
    }

    @Test
    @DisplayName("En el listado tampoco viaja el teléfono de un particular")
    void elListadoTampocoPublicaElTelefonoDeUnParticular() throws Exception {
        String cuerpo = directorio(zona).body();

        // El de la entidad sí está; hay uno solo de los dos en toda la respuesta.
        assertThat(cuerpo).contains(emailDe("particular"));
        assertThat(cuerpo.split("600111222", -1)).hasSize(2);
    }

    // ---------------------------------------------------------------------
    // La nota, y que sea la misma en los dos sitios
    // ---------------------------------------------------------------------

    /**
     * ES EL PUNTO 5 DEL BLOQUE. La media y el recuento salen del mismo
     * ValoracionService en los dos endpoints, pero eso es una afirmación sobre el
     * código: aquí se comprueba sobre la respuesta, con las valoraciones
     * insertadas por HTTP y las dos medias calculadas por PostgreSQL.
     */
    @Test
    @DisplayName("La media y el total del directorio son los mismos que los de GET /api/adopcion/{id}")
    void laNotaCoincideConLaDelDetalleDelAnimal() throws Exception {
        // Sin valoraciones: los dos omiten la media y dicen cero.
        assertThat(ficha(idActiva).body()).doesNotContain("\"valoracionMedia\"");
        assertThat(JsonPath.<Integer>read(ficha(idActiva).body(), "$.totalValoraciones")).isZero();
        assertThat(totalSegunElAnimal()).isZero();

        // Dos valoraciones reales: 5 y 4 -> 4,50.
        valorarCon(tokenCiudadano, 5);
        Long idOtro = registrarCiudadano("otro", "Luis");
        usuariosCreados.add(idOtro);
        String tokenOtro = entrar(emailDe("otro"));
        valorarCon(tokenOtro, 4);

        double mediaDelDirectorio = ((Number) JsonPath.read(
                ficha(idActiva).body(), "$.valoracionMedia")).doubleValue();
        int totalDelDirectorio = JsonPath.read(ficha(idActiva).body(), "$.totalValoraciones");

        assertThat(mediaDelDirectorio).isEqualTo(4.5);
        assertThat(totalDelDirectorio).isEqualTo(2);
        assertThat(mediaDelDirectorio).isEqualTo(mediaSegunElAnimal());
        assertThat(totalDelDirectorio).isEqualTo(totalSegunElAnimal());

        // Y el listado dice lo mismo que la ficha, que sale de otra consulta:
        // la del listado es una agregación en lote y la de la ficha, individual.
        HttpResponse<String> listado = directorio(zona);
        List<Number> mediaEnElListado = JsonPath.read(listado.body(),
                "$.content[?(@.id == " + idActiva + ")].valoracionMedia");
        assertThat(mediaEnElListado).hasSize(1);
        assertThat(mediaEnElListado.get(0).doubleValue()).isEqualTo(mediaDelDirectorio);
    }

    @Test
    @DisplayName("La ficha trae las valoraciones recibidas, con quién las emitió y sin su contacto")
    void laFichaTraeLasValoraciones() throws Exception {
        valorarCon(tokenCiudadano, 5);

        HttpResponse<String> respuesta = ficha(idActiva);

        assertThat(JsonPath.<List<Integer>>read(respuesta.body(), "$.valoraciones[*].puntuacion"))
                .containsExactly(5);
        assertThat(JsonPath.<List<String>>read(respuesta.body(), "$.valoraciones[*].autor.nombre"))
                .containsExactly("Ana");

        // La excepción de RF-06 es el contacto de la PROTECTORA, no el de quien
        // la valora: el de un ciudadano sigue sin salir nunca (RNF-10).
        assertThat(respuesta.body()).doesNotContain(emailDe("ciudadano"));
    }

    // ---------------------------------------------------------------------
    // El filtro de zona contra PostgreSQL
    // ---------------------------------------------------------------------

    /**
     * LA TRAMPA QUE YA COSTÓ UN FALLO REAL en el catálogo de adopción. Sin filtro,
     * el parámetro tiene que llegar a la consulta como "%" y no como null: un
     * nulo no tiene tipo, PostgreSQL lo toma por bytea y lower() muere. Solo se ve
     * ejecutando la consulta de verdad.
     */
    @Test
    @DisplayName("Sin filtro de zona la consulta no revienta y las incluye igual")
    void sinFiltroDeZonaFunciona() throws Exception {
        HttpResponse<String> listado = enviar("GET", "/api/protectoras?size=100",
                tokenCiudadano, null);

        assertThat(listado.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(listado.body(), "$.totalElements")).isPositive();
    }

    @Test
    @DisplayName("El filtro de zona busca por contenido y no distingue mayúsculas")
    void elFiltroDeZonaBuscaPorContenido() throws Exception {
        assertThat(idsDelListado(directorio(zona.toUpperCase())))
                .containsExactlyInAnyOrder(idActiva, idParticular);
        assertThat(idsDelListado(directorio("zona-" + sufijo)))
                .containsExactlyInAnyOrder(idActiva, idParticular);
        assertThat(idsDelListado(directorio(sufijo)))
                .containsExactlyInAnyOrder(idActiva, idParticular);
    }

    @Test
    @DisplayName("Una zona en blanco es no filtrar, no filtrar por vacío")
    void laZonaEnBlancoNoFiltra() throws Exception {
        HttpResponse<String> listado = enviar("GET", "/api/protectoras?zona=&size=100",
                tokenCiudadano, null);

        assertThat(listado.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(listado.body(), "$.totalElements")).isPositive();
    }

    @Test
    @DisplayName("Una zona sin protectoras devuelve una página vacía, no un error")
    void unaZonaSinProtectorasDevuelveVacio() throws Exception {
        HttpResponse<String> listado = directorio("no-existe-esta-zona-" + sufijo);

        assertThat(listado.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(listado.body(), "$.totalElements")).isZero();
    }

    // ---------------------------------------------------------------------
    // Los animales de la ficha
    // ---------------------------------------------------------------------

    /**
     * La ficha es un escaparate, no el histórico de gestión: eso es
     * /panel/animales, que sí enseña los tres estados.
     */
    @Test
    @DisplayName("La ficha solo enseña los animales DISPONIBLE, no los adoptados ni los retirados")
    void laFichaSoloEnsenaLosDisponibles() throws Exception {
        Long adoptado = publicarAnimal(tokenActiva, "Toby");
        animalesCreados.add(adoptado);
        assertThat(enviar("PUT", "/api/adopcion/" + adoptado + "/adoptado", tokenActiva, null)
                .statusCode()).isEqualTo(200);

        Long retirado = publicarAnimal(tokenActiva, "Nala");
        animalesCreados.add(retirado);
        assertThat(enviar("DELETE", "/api/adopcion/" + retirado, tokenActiva, null)
                .statusCode()).isEqualTo(204);

        HttpResponse<String> respuesta = ficha(idActiva);

        assertThat(JsonPath.<List<Integer>>read(respuesta.body(), "$.animales.content[*].id"))
                .containsExactly(idAnimal.intValue());
        assertThat(JsonPath.<Integer>read(respuesta.body(), "$.animales.totalElements"))
                .isEqualTo(1);

        // El panel de la misma protectora sí los ve los tres: es la diferencia
        // deliberada entre escaparate e histórico.
        HttpResponse<String> panel =
                enviar("GET", "/api/protectoras/panel/animales", tokenActiva, null);
        assertThat(JsonPath.<Integer>read(panel.body(), "$.totalElements")).isEqualTo(3);
    }

    @Test
    @DisplayName("page y size de la ficha paginan sus animales")
    void laFichaPaginaSusAnimales() throws Exception {
        animalesCreados.add(publicarAnimal(tokenActiva, "Toby"));
        animalesCreados.add(publicarAnimal(tokenActiva, "Nala"));

        HttpResponse<String> primera = enviar("GET",
                "/api/protectoras/" + idActiva + "?page=0&size=2", tokenCiudadano, null);

        assertThat(JsonPath.<List<Integer>>read(primera.body(), "$.animales.content[*].id"))
                .hasSize(2);
        assertThat(JsonPath.<Integer>read(primera.body(), "$.animales.totalElements")).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(primera.body(), "$.animales.last")).isFalse();
    }

    // ---------------------------------------------------------------------
    // Acceso
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Sin token no se lee el directorio: no es una fuente abierta de correos")
    void sinTokenNoSeLeeElDirectorio() throws Exception {
        assertThat(enviar("GET", "/api/protectoras", null, null).statusCode()).isEqualTo(401);
        assertThat(enviar("GET", "/api/protectoras/" + idActiva, null, null).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("No hace falta ningún rol: lo consulta el ciudadano y también la protectora")
    void cualquierAutenticadoLoConsulta() throws Exception {
        assertThat(directorio(zona).statusCode()).isEqualTo(200);
        assertThat(enviar("GET", "/api/protectoras?zona=" + zona, tokenActiva, null).statusCode())
                .isEqualTo(200);
        assertThat(enviar("GET", "/api/protectoras?zona=" + zona, tokenAdmin, null).statusCode())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private HttpResponse<String> directorio(String zonaBuscada) throws Exception {
        return enviar("GET", "/api/protectoras?size=100&zona="
                + java.net.URLEncoder.encode(zonaBuscada, StandardCharsets.UTF_8),
                tokenCiudadano, null);
    }

    private HttpResponse<String> ficha(Long protectoraId) throws Exception {
        return enviar("GET", "/api/protectoras/" + protectoraId, tokenCiudadano, null);
    }

    private List<Long> idsDelListado(HttpResponse<String> listado) {
        List<Integer> ids = JsonPath.read(listado.body(), "$.content[*].id");
        return ids.stream().map(Integer::longValue).toList();
    }

    /** Los animales que el escaparate público enseña en esta zona. */
    private List<Long> idsDelCatalogo() throws Exception {
        HttpResponse<String> catalogo =
                enviar("GET", "/api/adopcion?size=100&zona=" + zona, tokenCiudadano, null);
        assertThat(catalogo.statusCode()).isEqualTo(200);

        List<Integer> ids = JsonPath.read(catalogo.body(), "$.content[*].id");
        return ids.stream().map(Integer::longValue).toList();
    }

    /**
     * Recibe el token porque alguna de estas pruebas bloquea al ciudadano, y una
     * cuenta bloqueada deja de valer en la siguiente petición: JwtAuthFilter
     * recarga la fila del usuario en cada una, precisamente para eso (RF-11).
     */
    private String estadoDelAnimal(String token) throws Exception {
        HttpResponse<String> detalle =
                enviar("GET", "/api/adopcion/" + idAnimal, token, null);
        assertThat(detalle.statusCode()).isEqualTo(200);
        return JsonPath.read(detalle.body(), "$.estado");
    }

    /** Desliza a favor —el "contacto" que RF-23 exige— y después valora. */
    private void valorarCon(String token, int puntuacion) throws Exception {
        assertThat(enviar("POST", "/api/matches", token, """
                {"publicacionId":%d,"tipoPublicacion":"ADOPCION","interesado":true}
                """.formatted(idAnimal)).statusCode()).isEqualTo(201);

        assertThat(enviar("PUT", "/api/protectoras/" + idActiva + "/valoracion", token,
                "{\"puntuacion\":" + puntuacion + "}").statusCode()).isEqualTo(200);
    }

    private double mediaSegunElAnimal() throws Exception {
        HttpResponse<String> detalle =
                enviar("GET", "/api/adopcion/" + idAnimal, tokenCiudadano, null);
        return ((Number) JsonPath.read(detalle.body(), "$.protectoraValoracionMedia")).doubleValue();
    }

    private int totalSegunElAnimal() throws Exception {
        HttpResponse<String> detalle =
                enviar("GET", "/api/adopcion/" + idAnimal, tokenCiudadano, null);
        return JsonPath.read(detalle.body(), "$.protectoraValoracionTotal");
    }

    private String emailDe(String papel) {
        return papel + "-" + sufijo + "@test.es";
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

    private Long registrarCiudadano() throws Exception {
        Long id = registrarCiudadano("ciudadano", "Ana");
        usuariosCreados.add(id);
        return id;
    }

    private Long registrarCiudadano(String papel, String nombre) throws Exception {
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"%s","email":"%s","password":"%s"}
                """.formatted(nombre, emailDe(papel), CLAVE));
        assertThat(registro.statusCode()).isEqualTo(201);
        return numero(registro.body(), "$.id");
    }

    private Long registrarProtectora(String papel, String organizacion, boolean esPersonaFisica)
            throws Exception {

        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/protectora", null, """
                {"nombre":"Responsable","email":"%s","password":"%s","telefono":"600111222",
                 "nombreOrganizacion":"%s","direccion":"C/ Mayor 1",
                 "zonaGeografica":"%s","esPersonaFisica":%s}
                """.formatted(emailDe(papel), CLAVE, organizacion, zona, esPersonaFisica));
        assertThat(registro.statusCode()).isEqualTo(201);

        Long idCuenta = cuentaDe(papel);
        usuariosCreados.add(idCuenta);

        Long idProtectora = protectoraRepository.findByUsuarioId(idCuenta)
                .orElseThrow(() -> new IllegalStateException("la protectora no se ha guardado"))
                .getId();
        protectorasCreadas.add(idProtectora);
        return idProtectora;
    }

    private Long cuentaDe(String papel) {
        return usuarioRepository.findByEmail(emailDe(papel))
                .orElseThrow(() -> new IllegalStateException("la cuenta no se ha guardado"))
                .getId();
    }

    private void aprobar(Long protectoraId) throws Exception {
        assertThat(enviar("PUT", "/api/admin/protectoras/" + protectoraId + "/aprobar",
                tokenAdmin, null).statusCode()).isEqualTo(200);
    }

    private void rechazar(Long protectoraId) throws Exception {
        assertThat(enviar("PUT", "/api/admin/protectoras/" + protectoraId + "/rechazar",
                tokenAdmin, """
                        {"motivo":"Documentación incompleta"}
                        """).statusCode()).isEqualTo(200);
    }

    private Long publicarAnimal(String token, String nombre) throws Exception {
        HttpResponse<String> alta = enviar("POST", "/api/adopcion", token, """
                {"nombre":"%s","especie":"perro","tamanyo":"MEDIANO",
                 "descripcion":"Cariñosa y tranquila, busca familia",
                 "fotoUrl":"%s","zonaGeografica":"%s"}
                """.formatted(nombre, FotosDePrueba.subir(http, puerto, token), zona));
        assertThat(alta.statusCode()).isEqualTo(201);
        return numero(alta.body(), "$.id");
    }

    private String entrar(String email) throws Exception {
        HttpResponse<String> login = enviar("POST", "/api/auth/login", null, """
                {"email":"%s","password":"%s"}
                """.formatted(email, CLAVE));
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
