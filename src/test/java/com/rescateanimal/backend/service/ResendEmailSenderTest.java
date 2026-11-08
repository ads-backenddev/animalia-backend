package com.rescateanimal.backend.service;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ResendEmailSender contra un servidor HTTP simulado, el que trae el propio JDK
 * (com.sun.net.httpserver): ninguna prueba llama a Resend de verdad, y no hace
 * falta ninguna dependencia nueva para simularlo.
 *
 * Además de lo que se manda, se comprueba lo que NO llega al log: ni la clave
 * ni la dirección del destinatario, tampoco cuando la respuesta de error de
 * Resend la repite, como hace la de verdad ante un "to" mal formado.
 */
@ExtendWith(OutputCaptureExtension.class)
class ResendEmailSenderTest {

    private static final String CLAVE = "re_clave_de_prueba_0123456789abcdef";
    private static final String REMITENTE = "Animalia <avisos@animalia.test>";
    private static final String DESTINATARIO = "destinataria.de.prueba@example.com";

    private record Recibida(String metodo, String ruta, Headers cabeceras, String cuerpo) {
    }

    private final List<Recibida> recibidas = new CopyOnWriteArrayList<>();
    private final ExecutorService hilos = Executors.newCachedThreadPool();
    private HttpServer servidor;
    private volatile int estado = 200;
    private volatile long retrasoMs;

    @BeforeEach
    void arrancarServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.createContext("/", intercambio -> {
            recibidas.add(new Recibida(intercambio.getRequestMethod(), intercambio.getRequestURI().getPath(),
                    intercambio.getRequestHeaders(),
                    new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (retrasoMs > 0) {
                try {
                    Thread.sleep(retrasoMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // La forma de las respuestas de Resend, con la dirección dentro del error.
            byte[] respuesta = (estado == 200
                    ? "{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}"
                    : "{\"statusCode\":" + estado + ",\"name\":\"validation_error\","
                            + "\"message\":\"Invalid `to` field: " + DESTINATARIO + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado, respuesta.length);
            intercambio.getResponseBody().write(respuesta);
            intercambio.close();
        });
        servidor.setExecutor(hilos);
        servidor.start();
    }

    @AfterEach
    void pararServidor() {
        servidor.stop(0);
        hilos.shutdownNow();
    }

    @Test
    @DisplayName("Un envío es un POST /emails con la clave en Authorization y from, to, subject, html y text en JSON")
    void envioCorrecto(CapturedOutput salida) {
        assertThat(sender(Duration.ofSeconds(5)).enviar(correo())).isTrue();

        assertThat(recibidas).hasSize(1);
        Recibida peticion = recibidas.get(0);
        assertThat(peticion.metodo()).isEqualTo("POST");
        assertThat(peticion.ruta()).isEqualTo("/emails");
        assertThat(peticion.cabeceras().getFirst("Authorization")).isEqualTo("Bearer " + CLAVE);
        assertThat(peticion.cabeceras().getFirst("Content-Type")).startsWith("application/json");
        assertThat(JsonPath.<String>read(peticion.cuerpo(), "$.from")).isEqualTo(REMITENTE);
        assertThat(JsonPath.<List<String>>read(peticion.cuerpo(), "$.to")).containsExactly(DESTINATARIO);
        assertThat(JsonPath.<String>read(peticion.cuerpo(), "$.subject")).isEqualTo("Recupera tu contraseña");
        assertThat(JsonPath.<String>read(peticion.cuerpo(), "$.html")).isEqualTo("<p>Hola</p>");
        assertThat(JsonPath.<String>read(peticion.cuerpo(), "$.text")).isEqualTo("Hola");

        assertThat(salida).contains("Correo RECUPERACION_PASSWORD enviado por Resend")
                .doesNotContain(CLAVE).doesNotContain(DESTINATARIO);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401, 403, 422, 429, 500, 503})
    @DisplayName("Un 4xx, un 429 o un 5xx devuelven false y dejan en el log el código y el tipo, sin clave ni dirección")
    void rechazo(int codigo, CapturedOutput salida) {
        estado = codigo;

        assertThat(sender(Duration.ofSeconds(5)).enviar(correo())).isFalse();

        assertThat(salida).contains("Resend rechazó el correo RECUPERACION_PASSWORD: HTTP " + codigo)
                .doesNotContain(CLAVE).doesNotContain(DESTINATARIO);
    }

    @Test
    @DisplayName("Si Resend no contesta a tiempo, false y «tiempo de espera agotado» en el log")
    void tiempoDeEspera(CapturedOutput salida) {
        retrasoMs = 2_000;

        assertThat(sender(Duration.ofMillis(200)).enviar(correo())).isFalse();

        assertThat(salida).contains("No se pudo entregar el correo RECUPERACION_PASSWORD a Resend: tiempo de espera agotado")
                .doesNotContain(CLAVE).doesNotContain(DESTINATARIO);
    }

    @Test
    @DisplayName("Si no hay con quién conectar, false y «error de conexión» en el log")
    void sinConexion(CapturedOutput salida) {
        ResendEmailSender sender = sender(Duration.ofSeconds(5));
        servidor.stop(0);

        assertThat(sender.enviar(correo())).isFalse();

        assertThat(salida).contains("No se pudo entregar el correo RECUPERACION_PASSWORD a Resend: error de conexión")
                .doesNotContain(CLAVE).doesNotContain(DESTINATARIO);
    }

    @Test
    @DisplayName("Las esperas por defecto son 5 s para conectar y 10 s para la respuesta")
    void esperasPorDefecto() {
        assertThat(ResendEmailSender.ESPERA_CONEXION).isEqualTo(Duration.ofSeconds(5));
        assertThat(ResendEmailSender.ESPERA_RESPUESTA).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Sin RESEND_API_KEY o sin MAIL_FROM no se crea: el mensaje nombra la variable y nunca el valor")
    void sinVariablesNoArranca() {
        String url = "http://127.0.0.1:" + servidor.getAddress().getPort();

        assertThatThrownBy(() -> new ResendEmailSender(url, "", REMITENTE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
        assertThatThrownBy(() -> new ResendEmailSender(url, CLAVE, "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_FROM")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CLAVE));
        assertThatThrownBy(() -> new ResendEmailSender(url, CLAVE, "remitente-sin-arroba"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_FROM")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("remitente-sin-arroba").doesNotContain(CLAVE));
        assertThat(recibidas).as("ninguna llamada a Resend").isEmpty();
    }

    private ResendEmailSender sender(Duration esperaRespuesta) {
        return new ResendEmailSender("http://127.0.0.1:" + servidor.getAddress().getPort(), CLAVE, REMITENTE,
                Duration.ofSeconds(2), esperaRespuesta);
    }

    private static CorreoSaliente correo() {
        return new CorreoSaliente(CorreoSaliente.Tipo.RECUPERACION_PASSWORD, DESTINATARIO,
                "Recupera tu contraseña", "<p>Hola</p>", "Hola");
    }
}
