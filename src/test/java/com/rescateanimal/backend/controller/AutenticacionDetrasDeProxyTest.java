package com.rescateanimal.backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El límite de intentos por IP detrás de un proxy, con la estrategia de
 * cabeceras del perfil prod (native) y los topes reales: 30 por IP y 5 por
 * cuenta (informe ENS, C1).
 *
 * La prueba hace de proxy de confianza —conecta desde 127.0.0.1, que está en
 * los rangos privados que Tomcat da por buenos— y manda lo que mandaría el de
 * Railway: a la izquierda de X-Forwarded-For lo que el cliente haya querido
 * escribir, distinto en cada intento, y al final la dirección real que añade
 * el proxy. Cada intento va contra una cuenta distinta, para que el límite por
 * cuenta no salte antes: es el ataque de una contraseña común contra muchas
 * cuentas, que solo frena el límite por IP.
 *
 * Con la estrategia "framework", la que tenía prod, la IP era el primer valor
 * de la cabecera y los 31 intentos daban 401.
 */
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890",
                "server.forward-headers-strategy=native",
                "app.auth.max-intentos-ip=30",
                "app.auth.max-intentos-cuenta=5"
        })
class AutenticacionDetrasDeProxyTest {

    /** Direcciones de documentación (RFC 5737): no son de nadie. */
    private static final String CLIENTE_REAL = "203.0.113.7";
    private static final String OTRO_CLIENTE = "203.0.113.8";

    @LocalServerPort
    private int puerto;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String sufijo = UUID.randomUUID().toString().substring(0, 8);

    @Test
    @DisplayName("Inventar el principio de X-Forwarded-For no esquiva el límite por IP: el intento 31 da 429, y otro cliente sigue entrando")
    void laCabeceraInventadaNoEsquivaElLimite(CapturedOutput salida) throws Exception {
        for (int intento = 1; intento <= 30; intento++) {
            HttpResponse<String> respuesta = login(intento, "198.51.100." + intento + ", " + CLIENTE_REAL);
            assertThat(respuesta.statusCode()).as("intento %d", intento).isEqualTo(401);
        }

        HttpResponse<String> trigesimoPrimero = login(31, "198.51.100.231, " + CLIENTE_REAL);
        assertThat(trigesimoPrimero.statusCode()).isEqualTo(429);
        assertThat(JsonPath.<String>read(trigesimoPrimero.body(), "$.codigo")).isEqualTo("DEMASIADOS_INTENTOS");

        // El límite es de esa dirección y no del proxy: otro cliente, por el mismo proxy, entra.
        assertThat(login(32, "198.51.100.1, " + OTRO_CLIENTE).statusCode()).isEqualTo(401);

        // El corte queda en el registro de auditoría (C2) con la dirección real, no con la inventada.
        assertThat(salida).contains("evento=LIMITE_INTENTOS resultado=BLOQUEADO_POR_IP usuario=- ip=" + CLIENTE_REAL)
                .contains("evento=LOGIN resultado=CREDENCIALES_INVALIDAS usuario=- ip=" + CLIENTE_REAL)
                .doesNotContain("ip=198.51.100.");
    }

    private HttpResponse<String> login(int intento, String reenviadoPara) throws Exception {
        String cuerpo = """
                {"email":"proxy-%s-%d@example.com","password":"no-es-la-buena"}
                """.formatted(sufijo, intento);
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + "/api/auth/login"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Forwarded-For", reenviadoPara)
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
