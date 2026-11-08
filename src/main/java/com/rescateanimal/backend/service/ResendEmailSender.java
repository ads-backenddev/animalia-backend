package com.rescateanimal.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * Envío por la API HTTP de Resend (POST /emails), en el perfil prod.
 *
 * Por HTTPS y no por SMTP: la plataforma de despliegue no garantiza la salida
 * por los puertos de correo, y la API devuelve un código de estado que dice qué
 * ha pasado con cada envío.
 *
 * NO ARRANCA SIN SUS DOS VARIABLES. RESEND_API_KEY y MAIL_FROM se comprueban al
 * crear el bean: sin ellas producción arrancaría sin poder enviar un solo correo
 * de recuperación, y nadie lo sabría hasta el primer usuario que no lo recibe.
 * El mensaje nombra la variable, nunca su valor.
 *
 * ESPERAS ACOTADAS: 5 s para conectar y 10 s para la respuesta. El envío corre
 * en el ejecutor de tareas en segundo plano (EmailService es @Async), y sin
 * techo un proveedor colgado iría ocupando sus hilos uno a uno.
 *
 * QUÉ LLEGA AL LOG: el tipo de correo y el código de estado HTTP —o, si no hubo
 * respuesta, si fue el tiempo de espera o la conexión—. Ni la dirección del
 * destinatario ni la clave, y tampoco el cuerpo de la respuesta de error, que
 * puede repetir la dirección enviada.
 */
@Slf4j
@Component
@Profile("prod")
public class ResendEmailSender implements EmailSender {

    static final Duration ESPERA_CONEXION = Duration.ofSeconds(5);
    static final Duration ESPERA_RESPUESTA = Duration.ofSeconds(10);

    private final RestClient cliente;
    private final String remitente;

    /** El cuerpo que espera POST /emails de Resend. */
    record PeticionResend(String from, List<String> to, String subject, String html, String text) {
    }

    @Autowired
    public ResendEmailSender(@Value("${app.mail.resend.url:https://api.resend.com}") String url,
                             @Value("${app.mail.resend.api-key:}") String apiKey,
                             @Value("${app.mail.remitente:}") String remitente) {
        this(url, apiKey, remitente, ESPERA_CONEXION, ESPERA_RESPUESTA);
    }

    /** Con las esperas a medida, para que las pruebas no tarden diez segundos en ver un timeout. */
    ResendEmailSender(String url, String apiKey, String remitente,
                      Duration esperaConexion, Duration esperaRespuesta) {
        exigirVariable(apiKey, "RESEND_API_KEY", "la clave de la API de Resend, con permiso solo de envío");
        exigirVariable(remitente, "MAIL_FROM", "el remitente de los correos, de un dominio verificado en Resend");
        if (!remitente.contains("@")) {
            throw new IllegalStateException("La variable MAIL_FROM no es una dirección de correo: tiene que ser "
                    + "\"Nombre <dirección>\" o una dirección, de un dominio verificado en Resend");
        }

        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(esperaConexion).build());
        fabrica.setReadTimeout(esperaRespuesta);
        this.cliente = RestClient.builder()
                .baseUrl(url)
                .requestFactory(fabrica)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.remitente = remitente;
    }

    @Override
    public boolean enviar(CorreoSaliente correo) {
        try {
            cliente.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PeticionResend(remitente, List.of(correo.para()), correo.asunto(),
                            correo.html(), correo.texto()))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Correo {} enviado por Resend", correo.tipo());
            return true;
        } catch (RestClientResponseException e) {
            // 4xx —incluido el 429 del límite de envíos— y 5xx: el código basta para saber qué pasó.
            log.error("Resend rechazó el correo {}: HTTP {}", correo.tipo(), e.getStatusCode().value());
            return false;
        } catch (ResourceAccessException e) {
            log.error("No se pudo entregar el correo {} a Resend: {}", correo.tipo(),
                    esTiempoDeEspera(e) ? "tiempo de espera agotado" : "error de conexión");
            return false;
        } catch (RestClientException e) {
            log.error("No se pudo entregar el correo {} a Resend: {}", correo.tipo(), e.getClass().getSimpleName());
            return false;
        }
    }

    private static void exigirVariable(String valor, String variable, String que) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno " + variable + " (" + que
                    + "): el perfil prod envía el correo por Resend y no arranca sin ella");
        }
    }

    private static boolean esTiempoDeEspera(Throwable e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof HttpTimeoutException || causa instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
