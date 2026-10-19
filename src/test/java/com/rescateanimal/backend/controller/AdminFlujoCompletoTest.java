package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
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
 * Recorrido completo del panel de administración contra el servidor y PostgreSQL
 * reales (RF-11).
 *
 * EXISTE POR DOS COSAS QUE NINGÚN MOCK PUEDE DEMOSTRAR:
 *
 * 1. Que bloquear IMPIDE ENTRAR de verdad. AdminService solo pone activo=false;
 *    quien cierra la puerta son AuthService y JwtAuthFilter, que están en otra
 *    capa. Con repositorios simulados esos tres trozos nunca se tocan a la vez, y
 *    justamente la regla que pide RF-11 vive en la unión de los tres.
 *
 * 2. Que los tres tipos de notificación nuevos CABEN EN LA BASE DE DATOS. Son
 *    valores nuevos de un enum que Hibernate mapea a varchar con un CHECK, así
 *    que hasta que uno se inserta de verdad nadie sabe si la migración V6 los
 *    admite. Un mock de NotificacionRepository se los tragaría todos.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d") y
 * perfil "test" activo, como MatchFlujoCompletoTest.
 *
 * El administrador se crea a mano contra el repositorio porque no hay endpoint de
 * registro para el rol ADMIN, ni debe haberlo: se da de alta desde la base de
 * datos, como la cuenta raíz de cualquier panel.
 */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890")
class AdminFlujoCompletoTest {

    private static final Duration ESPERA = Duration.ofSeconds(10);
    private static final String CLAVE = "passw0rd123";

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private ProtectoraRepository protectoraRepository;
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
    private Long idAdmin;
    private Long idCiudadano;
    private Long idCuentaProtectora;
    private Long idProtectora;

    private final List<Long> usuariosCreados = new ArrayList<>();

    @BeforeEach
    void prepararEscenario() throws Exception {
        sufijo = UUID.randomUUID().toString().substring(0, 8);

        idAdmin = crearAdministrador();
        tokenAdmin = entrar(emailDe("admin"));

        idCiudadano = registrarCiudadano();
        tokenCiudadano = entrar(emailDe("ciudadano"));

        long[] protectora = registrarProtectora();
        idCuentaProtectora = protectora[0];
        idProtectora = protectora[1];
    }

    @AfterEach
    void limpiar() {
        notificacionRepository.deleteAll(notificacionRepository.findAll().stream()
                .filter(n -> usuariosCreados.contains(n.getUsuario().getId()))
                .toList());
        // Consultar la cuota diaria puede dejar su fila, y apunta al usuario.
        limiteMatchDiarioRepository.deleteAll(limiteMatchDiarioRepository.findAll().stream()
                .filter(l -> usuariosCreados.contains(l.getUsuario().getId()))
                .toList());
        if (idProtectora != null) {
            protectoraRepository.deleteById(idProtectora);
        }
        usuarioRepository.deleteAllById(usuariosCreados);
    }

    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Bloquear impide entrar, invalida el token ya emitido y notifica; desbloquear lo revierte")
    void bloquearImpideLogin() throws Exception {
        // 1. Antes del bloqueo, el ciudadano entra y su token vale.
        assertThat(enviar("POST", "/api/auth/login", null, credenciales(emailDe("ciudadano")))
                .statusCode()).isEqualTo(200);
        // Cualquier ruta autenticada sirve para ver si el token vale; se usa la
        // del límite diario porque responde a cualquier ciudadano sin más datos.
        assertThat(enviar("GET", "/api/matches/limite", tokenCiudadano, null).statusCode())
                .isEqualTo(200);

        // 2. El administrador lo bloquea.
        HttpResponse<String> bloqueo = enviar("PUT",
                "/api/admin/usuarios/" + idCiudadano + "/bloquear", tokenAdmin,
                """
                {"motivo":"Insultos reiterados en el chat"}
                """);
        assertThat(bloqueo.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(bloqueo.body(), "$.activo")).isFalse();

        // 3. Ya no puede iniciar sesión: 403 con el código de RF-11.
        HttpResponse<String> loginBloqueado =
                enviar("POST", "/api/auth/login", null, credenciales(emailDe("ciudadano")));
        assertThat(loginBloqueado.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(loginBloqueado.body(), "$.codigo"))
                .isEqualTo("CUENTA_BLOQUEADA");

        // 4. Y el token que ya tenía en la mano deja de servir en el acto, sin
        //    esperar a que caduque: JwtAuthFilter recarga el usuario cada vez.
        assertThat(enviar("GET", "/api/matches/limite", tokenCiudadano, null).statusCode())
                .isEqualTo(401);

        // 5. El aviso quedó guardado. Esto es lo que prueba que V6 admite el valor
        //    nuevo del enum: aquí se ha insertado contra el CHECK real.
        assertThat(tiposNotificadosA(idCiudadano)).containsExactly(TipoNotificacion.CUENTA_BLOQUEADA);

        // 6. Desbloquear lo revierte y vuelve a avisar.
        assertThat(enviar("PUT", "/api/admin/usuarios/" + idCiudadano + "/desbloquear",
                tokenAdmin, null).statusCode()).isEqualTo(200);

        assertThat(enviar("POST", "/api/auth/login", null, credenciales(emailDe("ciudadano")))
                .statusCode()).isEqualTo(200);
        assertThat(tiposNotificadosA(idCiudadano))
                .containsExactlyInAnyOrder(TipoNotificacion.CUENTA_BLOQUEADA,
                        TipoNotificacion.CUENTA_DESBLOQUEADA);
    }

    @Test
    @DisplayName("Aprobar una protectora la deja entrar en su panel y le notifica PROTECTORA_VERIFICADA")
    void aprobarProtectoraGeneraLaNotificacion() throws Exception {
        // Recién registrada está PENDIENTE, así que ni siquiera puede entrar.
        HttpResponse<String> loginPendiente =
                enviar("POST", "/api/auth/login", null, credenciales(emailDe("protectora")));
        assertThat(loginPendiente.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(loginPendiente.body(), "$.codigo"))
                .isEqualTo("PROTECTORA_PENDIENTE");

        // Y aparece en la cola de verificación del administrador.
        HttpResponse<String> cola =
                enviar("GET", "/api/admin/protectoras/pendientes", tokenAdmin, null);
        assertThat(cola.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(cola.body(), "$[*].id"))
                .contains(idProtectora.intValue());

        // El administrador la aprueba.
        HttpResponse<String> aprobacion = enviar("PUT",
                "/api/admin/protectoras/" + idProtectora + "/aprobar", tokenAdmin, null);
        assertThat(aprobacion.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(aprobacion.body(), "$.estado")).isEqualTo("ACTIVA");

        assertThat(tiposNotificadosA(idCuentaProtectora))
                .containsExactly(TipoNotificacion.PROTECTORA_VERIFICADA);

        // Y ahora sí entra, y su panel se abre: la aprobación surte efecto entera.
        String token = entrar(emailDe("protectora"));
        assertThat(enviar("GET", "/api/protectoras/panel/resumen", token, null).statusCode())
                .isEqualTo(200);

        // Ya no está en la cola de pendientes.
        assertThat(JsonPath.<List<Integer>>read(
                enviar("GET", "/api/admin/protectoras/pendientes", tokenAdmin, null).body(),
                "$[*].id")).doesNotContain(idProtectora.intValue());
    }

    @Test
    @DisplayName("Rechazar una protectora ya activa le cierra el panel en el acto y le notifica")
    void rechazarProtectoraCierraElPanel() throws Exception {
        assertThat(enviar("PUT", "/api/admin/protectoras/" + idProtectora + "/aprobar",
                tokenAdmin, null).statusCode()).isEqualTo(200);
        String token = entrar(emailDe("protectora"));
        assertThat(enviar("GET", "/api/protectoras/panel/resumen", token, null).statusCode())
                .isEqualTo(200);

        // El administrador le retira la verificación.
        HttpResponse<String> rechazo = enviar("PUT",
                "/api/admin/protectoras/" + idProtectora + "/rechazar", tokenAdmin, """
                {"motivo":"Denuncias confirmadas por maltrato"}
                """);
        assertThat(rechazo.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(rechazo.body(), "$.estado")).isEqualTo("RECHAZADA");

        // El token sigue siendo válido, pero el panel se comprueba en CADA
        // petición: por eso deja de abrirse sin esperar a que caduque nada.
        HttpResponse<String> panel = enviar("GET", "/api/protectoras/panel/resumen", token, null);
        assertThat(panel.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(panel.body(), "$.codigo")).isEqualTo("PROTECTORA_RECHAZADA");

        // Los dos avisos, en orden: la verificación y su retirada. Este es el
        // segundo valor nuevo de V6 insertado contra el CHECK real.
        assertThat(tiposNotificadosA(idCuentaProtectora))
                .containsExactlyInAnyOrder(TipoNotificacion.PROTECTORA_VERIFICADA,
                        TipoNotificacion.PROTECTORA_RECHAZADA);
    }

    @Test
    @DisplayName("Sin rol ADMIN no se entra en el panel ni con un token válido de otro rol")
    void sinRolAdminNoSeEntra() throws Exception {
        // Con la cadena de filtros REAL, no con addFilters=false: aquí se
        // comprueba que el token de un ciudadano legítimo tampoco vale.
        assertThat(enviar("GET", "/api/admin/usuarios", tokenCiudadano, null).statusCode())
                .isEqualTo(403);
        assertThat(enviar("GET", "/api/admin/protectoras/pendientes", tokenCiudadano, null)
                .statusCode()).isEqualTo(403);
        assertThat(enviar("PUT", "/api/admin/usuarios/" + idAdmin + "/bloquear", tokenCiudadano, """
                {"motivo":"quiero mandar yo"}
                """).statusCode()).isEqualTo(403);

        // Y sin token, 401: no se llega ni a mirar el rol.
        assertThat(enviar("GET", "/api/admin/usuarios", null, null).statusCode()).isEqualTo(401);

        // Nada de esto dejó rastro: el administrador sigue operativo.
        assertThat(usuarioRepository.findById(idAdmin).orElseThrow().getActivo()).isTrue();
    }

    @Test
    @DisplayName("Un administrador no puede bloquearse a sí mismo")
    void elAdminNoSeBloqueaASiMismo() throws Exception {
        HttpResponse<String> respuesta = enviar("PUT",
                "/api/admin/usuarios/" + idAdmin + "/bloquear", tokenAdmin, """
                {"motivo":"Me castigo"}
                """);

        assertThat(respuesta.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(respuesta.body(), "$.codigo")).isEqualTo("AUTOBLOQUEO");
        assertThat(usuarioRepository.findById(idAdmin).orElseThrow().getActivo()).isTrue();
    }

    // ---------------------------------------------------------------------

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

    /** No hay endpoint de registro para ADMIN, y no debe haberlo: se crea aquí. */
    private Long crearAdministrador() {
        Usuario admin = new Usuario();
        admin.setNombre("Administrador");
        admin.setEmail(emailDe("admin"));
        admin.setPasswordHash(passwordEncoder.encode(CLAVE));
        admin.setRol(Rol.ADMIN);
        admin.setActivo(true);

        Long id = usuarioRepository.save(admin).getId();
        usuariosCreados.add(id);
        return id;
    }

    private Long registrarCiudadano() throws Exception {
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/ciudadano", null, """
                {"nombre":"Ana","email":"%s","password":"%s"}
                """.formatted(emailDe("ciudadano"), CLAVE));
        assertThat(registro.statusCode()).isEqualTo(201);

        Long id = numero(registro.body(), "$.id");
        usuariosCreados.add(id);
        return id;
    }

    /** @return {id de la cuenta, id de la protectora} */
    private long[] registrarProtectora() throws Exception {
        HttpResponse<String> registro = enviar("POST", "/api/auth/registro/protectora", null, """
                {"nombre":"Refugio","email":"%s","password":"%s",
                 "nombreOrganizacion":"Huellas %s","direccion":"C/ Mayor 1",
                 "zonaGeografica":"Madrid"}
                """.formatted(emailDe("protectora"), CLAVE, sufijo));
        assertThat(registro.statusCode()).isEqualTo(201);

        // Se busca por la cuenta y no recorriendo protectoraRepository.findAll():
        // Protectora.usuario es LAZY, y fuera de una transacción leerlo de una
        // entidad ya desconectada revienta con LazyInitializationException.
        Long cuentaId = usuarioRepository.findByEmail(emailDe("protectora"))
                .orElseThrow(() -> new IllegalStateException("la cuenta no se ha guardado"))
                .getId();
        Long protectoraId = protectoraRepository.findByUsuarioId(cuentaId)
                .orElseThrow(() -> new IllegalStateException("la protectora no se ha guardado"))
                .getId();

        usuariosCreados.add(cuentaId);
        return new long[]{cuentaId, protectoraId};
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
