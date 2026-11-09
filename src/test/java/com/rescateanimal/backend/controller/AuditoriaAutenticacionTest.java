package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El registro de auditoría de la autenticación contra el servidor real
 * (informe ENS, C2): registro, login fallido y correcto, login con un email que
 * no existe, solicitud de recuperación y logout. Cada uno deja su línea, con
 * el id de la cuenta y la IP de la petición, y en ninguna parte del log —ni en
 * esas líneas ni en las demás— aparecen el email ni el token.
 */
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890")
class AuditoriaAutenticacionTest {

    @LocalServerPort
    private int puerto;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String email = "auditoria-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

    @AfterEach
    void limpiar() {
        usuarioRepository.findByEmail(email).ifPresent(usuarioRepository::delete);
    }

    @Test
    @DisplayName("Cada evento de autenticación deja una línea con el id y la IP, y el log no lleva ni el email ni el token")
    void cadaEventoDejaSuLinea(CapturedOutput salida) throws Exception {
        HttpResponse<String> registro = post("/api/auth/registro/ciudadano", null, """
                {"nombre":"Auditada","email":"%s","password":"passw0rd123"}
                """.formatted(email));
        assertThat(registro.statusCode()).isEqualTo(201);
        long id = ((Number) JsonPath.read(registro.body(), "$.id")).longValue();

        assertThat(post("/api/auth/login", null, login("no-es-la-buena")).statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/login", null, """
                {"email":"nadie-%s","password":"passw0rd123"}
                """.formatted(email)).statusCode()).isEqualTo(401);
        HttpResponse<String> entrada = post("/api/auth/login", null, login("passw0rd123"));
        assertThat(entrada.statusCode()).isEqualTo(200);
        String token = JsonPath.read(entrada.body(), "$.token");
        assertThat(post("/api/auth/recuperar-password", null, """
                {"email":"%s"}
                """.formatted(email)).statusCode()).isEqualTo(204);
        assertThat(post("/api/auth/logout", token, null).statusCode()).isEqualTo(204);

        List<String> lineas = salida.getOut().lines().filter(linea -> linea.contains("evento=")).toList();
        String ip = " ip=127.0.0.1";
        assertThat(lineas).anyMatch(l -> l.contains("evento=REGISTRO resultado=OK usuario=" + id + ip))
                .anyMatch(l -> l.contains("evento=LOGIN resultado=CREDENCIALES_INVALIDAS usuario=" + id + ip))
                .anyMatch(l -> l.contains("evento=LOGIN resultado=CREDENCIALES_INVALIDAS usuario=-" + ip))
                .anyMatch(l -> l.contains("evento=LOGIN resultado=OK usuario=" + id + ip))
                .anyMatch(l -> l.contains("evento=RECUPERACION_SOLICITADA resultado=OK usuario=" + id + ip))
                .anyMatch(l -> l.contains("evento=LOGOUT resultado=OK usuario=" + id + ip));
        assertThat(salida.getAll()).doesNotContain(email).doesNotContain(token);
    }

    private String login(String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private HttpResponse<String> post(String ruta, String token, String cuerpo) throws Exception {
        HttpRequest.Builder peticion = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + puerto + ruta))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8));
        if (token != null) {
            peticion.header("Authorization", "Bearer " + token);
        }
        return http.send(peticion.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
