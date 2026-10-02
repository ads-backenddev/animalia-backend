package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.FotosDePrueba;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.MensajeRepository;
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
 * Recorrido completo del perfil y de la baja de cuenta contra el servidor y
 * PostgreSQL reales (RF-02, RF-18, RF-19).
 *
 * EXISTE POR TRES COSAS QUE NINGÚN MOCK PUEDE DEMOSTRAR:
 *
 * 1. QUE EL HISTÓRICO SOBREVIVE A LA BAJA. Es la prueba que justifica todo el
 *    diseño de RF-19: dos personas hablan por un chat, una se da de baja, y la
 *    otra tiene que seguir leyendo la conversación entera con el nombre ya
 *    anonimizado. Con repositorios simulados no hay chat, ni mensajes, ni claves
 *    ajenas que pudieran arrastrarlos, así que la única forma de comprobarlo es
 *    montarlo de verdad.
 *
 * 2. QUE LA BAJA CIERRA LAS SESIONES SIN REVOCAR NADA. UsuarioService solo pone
 *    activo=false; quien cierra la puerta es JwtAuthFilter, que está en otra capa
 *    y recarga el usuario en cada petición. Los dos trozos solo se tocan a la vez
 *    con el servidor levantado.
 *
 * 3. QUE LA MIGRACIÓN V11 SE APLICA y su CHECK admite los cinco idiomas. Es un
 *    CHECK, y `validate` no comprueba los CHECK: hasta que no se inserta uno de
 *    verdad, nadie sabe si la restricción los acepta.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d") y
 * perfil "test" activo, como el resto de pruebas de extremo a extremo.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GestionCuentaFlujoCompletoTest {

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
    private PasswordEncoder passwordEncoder;
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

    private final HttpClient http = HttpClient.newHttpClient();

    private String sufijo;
    private String tokenDuenyo;
    private String tokenTestigo;
    private Long idDuenyo;
    private Long idTestigo;
    private Long idAlerta;
    private Long idAvistamiento;
    private String tokenAdmin;

    private final List<Long> usuariosCreados = new ArrayList<>();
    private final List<Long> protectorasCreadas = new ArrayList<>();

    @BeforeEach
    void prepararEscenario() throws Exception {
        sufijo = UUID.randomUUID().toString().substring(0, 8);

        idDuenyo = registrar("duenyo", "Marta");
        tokenDuenyo = entrar(emailDe("duenyo"));

        idTestigo = registrar("testigo", "Carlos");
        tokenTestigo = entrar(emailDe("testigo"));
    }

    @AfterEach
    void limpiar() {
        // En orden de dependencia: mensaje -> chat -> match -> publicaciones.
        //
        // Los chats se buscan por el id del match y NO recorriendo c.getMatch():
        // esa asociación es LAZY, y leerla sobre una entidad ya desconectada
        // revienta con LazyInitializationException.
        List<Long> matchesPropios = matchRegistroRepository.findAll().stream()
                .filter(m -> usuariosCreados.contains(m.getUsuario().getId()))
                .map(m -> m.getId())
                .toList();
        List<Long> chats = chatRepository.findByMatchIdIn(matchesPropios).stream()
                .map(c -> c.getId())
                .toList();
        // getChat().getId() sí es seguro sobre una entidad desconectada: pedirle
        // el id a un proxy no lo inicializa, a diferencia de cualquier otro campo.
        mensajeRepository.deleteAll(mensajeRepository.findAll().stream()
                .filter(m -> chats.contains(m.getChat().getId()))
                .toList());
        chatRepository.deleteAllById(chats);
        matchRegistroRepository.deleteAll(matchRegistroRepository.findAll().stream()
                .filter(m -> usuariosCreados.contains(m.getUsuario().getId()))
                .toList());
        notificacionRepository.deleteAll(notificacionRepository.findAll().stream()
                .filter(n -> usuariosCreados.contains(n.getUsuario().getId()))
                .toList());
        limiteMatchDiarioRepository.deleteAll(limiteMatchDiarioRepository.findAll().stream()
                .filter(l -> usuariosCreados.contains(l.getUsuario().getId()))
                .toList());
        if (idAvistamiento != null) {
            avistamientoRepository.deleteById(idAvistamiento);
        }
        if (idAlerta != null) {
            alertaRepository.deleteById(idAlerta);
        }
        protectoraRepository.deleteAllById(protectorasCreadas);
        FotosDePrueba.borrarLasDe(usuariosCreados, fotoRepository, almacenFotos);
        usuarioRepository.deleteAllById(usuariosCreados);
    }

    // ---------------------------------------------------------------------
    // RF-02 y RF-18
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("El perfil se lee y se actualiza, y el email no se puede cambiar por ahí")
    void elPerfilSeActualiza() throws Exception {
        HttpResponse<String> antes = enviar("GET", "/api/usuarios/me", tokenDuenyo, null);
        assertThat(antes.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(antes.body(), "$.nombre")).isEqualTo("Marta");

        HttpResponse<String> despues = enviar("PUT", "/api/usuarios/me", tokenDuenyo, """
                {"nombre":"Marta Ruiz","telefono":"600111222","areaGeografica":"Getafe",
                 "fotoUrl":"%s"}
                """.formatted(FotosDePrueba.subir(http, puerto, tokenDuenyo)));
        assertThat(despues.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(despues.body(), "$.nombre")).isEqualTo("Marta Ruiz");
        assertThat(JsonPath.<String>read(despues.body(), "$.areaGeografica")).isEqualTo("Getafe");

        // El email sigue siendo el de siempre aunque se intente colar en el cuerpo:
        // el DTO no tiene ese campo, así que Jackson lo ignora.
        HttpResponse<String> conEmail = enviar("PUT", "/api/usuarios/me", tokenDuenyo, """
                {"nombre":"Marta Ruiz","email":"otra@test.es"}
                """);
        assertThat(conEmail.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(conEmail.body(), "$.email")).isEqualTo(emailDe("duenyo"));

        // Y se puede seguir entrando con el de siempre.
        assertThat(enviar("POST", "/api/auth/login", null, credenciales(emailDe("duenyo")))
                .statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Los cinco idiomas caben en la base de datos; uno inventado da 400")
    void elIdiomaSeCambiaYSeValida() throws Exception {
        // Esto es lo que comprueba el CHECK de V11: hasta que no se inserta uno de
        // verdad nadie sabe si la restricción los admite.
        for (String codigo : List.of("es", "en", "ca", "gl", "eu")) {
            HttpResponse<String> respuesta = enviar("PUT", "/api/usuarios/me/idioma", tokenDuenyo,
                    "{\"idioma\":\"" + codigo + "\"}");
            assertThat(respuesta.statusCode()).as("idioma %s", codigo).isEqualTo(200);
            assertThat(JsonPath.<String>read(respuesta.body(), "$.idioma")).isEqualTo(codigo);
        }

        HttpResponse<String> invalido =
                enviar("PUT", "/api/usuarios/me/idioma", tokenDuenyo, """
                        {"idioma":"klingon"}
                        """);
        assertThat(invalido.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(invalido.body(), "$.mensaje")).contains("es, en, ca, gl, eu");

        // El rechazo no deja rastro: sigue en el último válido.
        assertThat(JsonPath.<String>read(
                enviar("GET", "/api/usuarios/me", tokenDuenyo, null).body(), "$.idioma"))
                .isEqualTo("eu");
    }

    // ---------------------------------------------------------------------
    // RF-19
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Tras la baja, el otro participante sigue leyendo el chat con el nombre anonimizado")
    void elHistoricoDelChatSobreviveALaBaja() throws Exception {
        long idChat = montarChatEntreLosDos();

        // 1. Antes de la baja, el testigo ve a Marta con su nombre.
        HttpResponse<String> antes = enviar("GET", "/api/chats/" + idChat, tokenTestigo, null);
        assertThat(antes.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(antes.body(), "$.otroParticipante.nombre"))
                .isEqualTo("Marta");

        // 2. Marta se da de baja.
        assertThat(enviar("DELETE", "/api/usuarios/me", tokenDuenyo,
                "{\"password\":\"" + CLAVE + "\"}").statusCode()).isEqualTo(204);

        // 3. ESTO ES LO QUE JUSTIFICA TODO EL DISEÑO: el chat sigue ahí, legible
        //    y completo, para quien no se ha ido. Un DELETE en cascada se lo
        //    habría llevado por delante junto con la conversación de otra persona.
        HttpResponse<String> despues = enviar("GET", "/api/chats/" + idChat, tokenTestigo, null);
        assertThat(despues.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(despues.body(), "$.otroParticipante.nombre"))
                .as("ya no es Marta, es una cuenta sin dueño")
                .isEqualTo("Usuario eliminado");
        assertThat(despues.body())
                .as("ni rastro del nombre real en la respuesta")
                .doesNotContain("Marta");

        // 4. Y los mensajes que se cruzaron siguen enteros.
        HttpResponse<String> mensajes =
                enviar("GET", "/api/chats/" + idChat + "/mensajes", tokenTestigo, null);
        assertThat(mensajes.statusCode()).isEqualTo(200);
        assertThat(mensajes.body()).contains("¿Sigue perdida?");
    }

    @Test
    @DisplayName("La baja borra los datos personales de la fila, pero no la fila")
    void laFilaSeConservaSinDatosPersonales() throws Exception {
        assertThat(enviar("DELETE", "/api/usuarios/me", tokenDuenyo,
                "{\"password\":\"" + CLAVE + "\"}").statusCode()).isEqualTo(204);

        Usuario anonimizado = usuarioRepository.findById(idDuenyo).orElseThrow();

        assertThat(anonimizado.getNombre()).isEqualTo("Usuario eliminado");
        assertThat(anonimizado.getTelefono()).isNull();
        assertThat(anonimizado.getFotoUrl()).isNull();
        assertThat(anonimizado.getActivo()).isFalse();
        assertThat(anonimizado.getEmail())
                .doesNotContain("duenyo")
                .endsWith("@cuenta-eliminada.invalid");
        assertThat(anonimizado.getCreatedAt())
                .as("la fecha de alta no es un dato personal y explica el histórico")
                .isNotNull();
    }

    @Test
    @DisplayName("La baja mata la sesión viva sin revocar nada, y el login deja de funcionar")
    void laBajaCierraLaSesion() throws Exception {
        // El token vale antes.
        assertThat(enviar("GET", "/api/usuarios/me", tokenDuenyo, null).statusCode()).isEqualTo(200);

        assertThat(enviar("DELETE", "/api/usuarios/me", tokenDuenyo,
                "{\"password\":\"" + CLAVE + "\"}").statusCode()).isEqualTo(204);

        // Y deja de valer en la siguiente petición, sin haber revocado el token:
        // JwtAuthFilter recarga la fila y la ve inactiva. Es la misma maquinaria
        // que el bloqueo del administrador (RF-11).
        assertThat(enviar("GET", "/api/usuarios/me", tokenDuenyo, null).statusCode()).isEqualTo(401);

        // Y ya no se puede volver a entrar: ni con la contraseña de siempre.
        assertThat(enviar("POST", "/api/auth/login", null, credenciales(emailDe("duenyo")))
                .statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Sin la contraseña correcta la cuenta no se borra")
    void sinLaClaveCorrectaNoSeBorra() throws Exception {
        HttpResponse<String> intento = enviar("DELETE", "/api/usuarios/me", tokenDuenyo, """
                {"password":"la-que-no-es"}
                """);

        assertThat(intento.statusCode()).isEqualTo(401);

        // La cuenta sigue entera y operativa.
        Usuario intacto = usuarioRepository.findById(idDuenyo).orElseThrow();
        assertThat(intacto.getNombre()).isEqualTo("Marta");
        assertThat(intacto.getActivo()).isTrue();
        assertThat(enviar("GET", "/api/usuarios/me", tokenDuenyo, null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Sin cuerpo, el DELETE no borra: la confirmación es obligatoria")
    void sinCuerpoNoSeBorra() throws Exception {
        assertThat(enviar("DELETE", "/api/usuarios/me", tokenDuenyo, """
                {"password":""}
                """).statusCode()).isEqualTo(400);

        assertThat(usuarioRepository.findById(idDuenyo).orElseThrow().getActivo()).isTrue();
    }

    @Test
    @DisplayName("El email de una cuenta dada de baja NO queda libre para otro")
    void elEmailNoQuedaLibre() throws Exception {
        String emailDeMarta = emailDe("duenyo");

        assertThat(enviar("DELETE", "/api/usuarios/me", tokenDuenyo,
                "{\"password\":\"" + CLAVE + "\"}").statusCode()).isEqualTo(204);

        // Si quedara libre, quien lo registrara aparecería ante el testigo como la
        // misma persona con la que estuvo hablando: el histórico se conserva justo
        // para que esas conversaciones sigan teniendo sentido.
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"Impostor","email":"%s","password":"%s"}
                """.formatted(emailDeMarta, CLAVE));

        assertThat(registro.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(registro.body(), "$.mensaje"))
                .as("el mensaje no delata que esa cuenta se dio de baja")
                .isEqualTo("Ya existe una cuenta con ese email");
    }

    @Test
    @DisplayName("Nadie puede registrarse en el dominio reservado de las cuentas eliminadas")
    void elDominioReservadoNoEsRegistrable() throws Exception {
        // SIN ESTO SE PODIA DEJAR UNA MINA. Los emails anonimizados son
        // deterministas, asi que quien conociera el email de otra persona podia
        // calcular su hash, registrarse con esa direccion, y el dia que la victima
        // quisiera darse de baja el UPDATE de su email chocaria contra el indice
        // unico: se le impedia ejercer RF-19 indefinidamente, con un 409 que no
        // explicaba nada.
        String hashDeOtro = "a".repeat(64) + "@cuenta-eliminada.invalid";

        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"Impostor","email":"%s","password":"%s"}
                """.formatted(hashDeOtro, CLAVE));

        assertThat(registro.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("Una protectora que es persona física pierde también nombre y dirección")
    void laProtectoraPersonaFisicaSeAnonimizaEntera() throws Exception {
        long[] particular = registrarProtectora("particular", "María Gómez Ruiz",
                "C/ del Olmo 14, 3ºB", true);
        String token = entrar(emailDe("particular"));

        assertThat(enviar("DELETE", "/api/usuarios/me", token,
                "{\"password\":\"" + CLAVE + "\"}").statusCode()).isEqualTo(204);

        Protectora anonimizada = protectoraRepository.findById(particular[1]).orElseThrow();
        assertThat(anonimizada.getNombreOrganizacion()).isEqualTo("Protectora eliminada");
        assertThat(anonimizada.getDireccion()).isNull();
        assertThat(anonimizada.getZonaGeografica())
                .as("una comarca no identifica a nadie y sostiene el histórico")
                .isEqualTo("Madrid");
    }

    @Test
    @DisplayName("Una protectora que es entidad conserva su nombre y su dirección")
    void laProtectoraEntidadConservaSusDatos() throws Exception {
        long[] asociacion = registrarProtectora("asociacion", "Huellas de Getafe",
                "C/ Mayor 1", false);
        String token = entrar(emailDe("asociacion"));

        assertThat(enviar("DELETE", "/api/usuarios/me", token,
                "{\"password\":\"" + CLAVE + "\"}").statusCode()).isEqualTo(204);

        // Son datos de una organización, no de una persona, y siguen explicando de
        // dónde salió cada animal que publicó.
        Protectora intacta = protectoraRepository.findById(asociacion[1]).orElseThrow();
        assertThat(intacta.getNombreOrganizacion()).isEqualTo("Huellas de Getafe");
        assertThat(intacta.getDireccion()).isEqualTo("C/ Mayor 1");
    }

    @Test
    @DisplayName("Sin declararlo, una protectora nace como entidad")
    void sinDeclararloEsEntidad() throws Exception {
        long[] callada = registrarProtectora("callada", "Refugio del Sur", "C/ Sur 3", null);

        assertThat(protectoraRepository.findById(callada[1]).orElseThrow().getEsPersonaFisica())
                .as("ausente equivale a no: es el caso mayoritario y conserva el histórico")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /** @return {id de la cuenta, id de la protectora} */
    private long[] registrarProtectora(String papel, String nombreOrganizacion,
                                       String direccion, Boolean esPersonaFisica) throws Exception {

        String declaracion = esPersonaFisica == null
                ? ""
                : ",\"esPersonaFisica\":" + esPersonaFisica;

        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/protectora", null, """
                {"nombre":"Titular","email":"%s","password":"%s",
                 "nombreOrganizacion":"%s","direccion":"%s","zonaGeografica":"Madrid"%s}
                """.formatted(emailDe(papel), CLAVE, nombreOrganizacion, direccion, declaracion));
        assertThat(registro.statusCode()).isEqualTo(201);

        Long cuentaId = usuarioRepository.findByEmail(emailDe(papel)).orElseThrow().getId();
        usuariosCreados.add(cuentaId);

        Long protectoraId = protectoraRepository.findByUsuarioId(cuentaId).orElseThrow().getId();
        protectorasCreadas.add(protectoraId);

        // Sin aprobar no puede ni entrar; la baja no depende del estado, pero el
        // login sí.
        aprobarProtectora(protectoraId);
        return new long[]{cuentaId, protectoraId};
    }

    /** El administrador se crea a mano: no hay endpoint de alta para ADMIN. */
    private void aprobarProtectora(Long protectoraId) throws Exception {
        if (tokenAdmin == null) {
            Usuario admin = new Usuario();
            admin.setNombre("Administrador");
            admin.setEmail(emailDe("admin"));
            admin.setPasswordHash(passwordEncoder.encode(CLAVE));
            admin.setRol(Rol.ADMIN);
            admin.setActivo(true);
            admin.setIdioma("es");
            usuariosCreados.add(usuarioRepository.save(admin).getId());
            tokenAdmin = entrar(emailDe("admin"));
        }
        assertThat(enviar("PUT", "/api/admin/protectoras/" + protectoraId + "/aprobar",
                tokenAdmin, null).statusCode()).isEqualTo(200);
    }

    /**
     * Monta el escenario mínimo con un chat entre los dos: una alerta del dueño,
     * un avistamiento del testigo sobre ella, el swipe a favor que los empareja y
     * un mensaje de cada uno.
     */
    private long montarChatEntreLosDos() throws Exception {
        HttpResponse<String> alerta = enviar("POST", "/api/alertas", tokenDuenyo, """
                {"descripcion":"Perrita marrón con collar rojo","especie":"perro",
                 "fechaAvistamiento":"2026-08-20T10:00:00Z",
                 "ubicacion":{"latitud":40.4168,"longitud":-3.7038}}
                """);
        assertThat(alerta.statusCode()).isEqualTo(201);
        idAlerta = numero(alerta.body(), "$.id");

        HttpResponse<String> avistamiento = enviar("POST", "/api/avistamientos", tokenTestigo, """
                {"alertaId":%d,"descripcion":"La he visto cerca del parque",
                 "fotoUrl":"%s",
                 "ubicacion":{"latitud":40.4170,"longitud":-3.7040}}
                """.formatted(idAlerta, FotosDePrueba.subir(http, puerto, tokenTestigo)));
        assertThat(avistamiento.statusCode()).isEqualTo(201);
        idAvistamiento = numero(avistamiento.body(), "$.id");

        // El dueño desliza a favor del avistamiento: eso crea el match y el chat.
        HttpResponse<String> swipe = enviar("POST", "/api/matches", tokenDuenyo, """
                {"publicacionId":%d,"tipoPublicacion":"AVISTAMIENTO","interesado":true}
                """.formatted(idAvistamiento));
        assertThat(swipe.statusCode()).isEqualTo(201);

        long idChat = numero(
                enviar("GET", "/api/chats", tokenDuenyo, null).body(), "$[0].id");

        assertThat(enviar("POST", "/api/chats/" + idChat + "/mensajes", tokenTestigo, """
                {"contenido":"La vi ayer por la tarde"}
                """).statusCode()).isEqualTo(201);
        assertThat(enviar("POST", "/api/chats/" + idChat + "/mensajes", tokenDuenyo, """
                {"contenido":"¿Sigue perdida?"}
                """).statusCode()).isEqualTo(201);

        return idChat;
    }

    private String emailDe(String papel) {
        return papel + "-" + sufijo + "@test.es";
    }

    private static String credenciales(String email) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, CLAVE);
    }

    private Long registrar(String papel, String nombre) throws Exception {
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"%s","email":"%s","password":"%s","telefono":"600000000",
                 "areaGeografica":"Madrid"}
                """.formatted(nombre, emailDe(papel), CLAVE));
        assertThat(registro.statusCode()).isEqualTo(201);

        Long id = numero(registro.body(), "$.id");
        usuariosCreados.add(id);
        return id;
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
