package com.rescateanimal.backend.config;

import com.rescateanimal.backend.service.EmailSender;
import com.rescateanimal.backend.service.ResendEmailSender;
import com.rescateanimal.backend.service.SmtpEmailSender;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo que de verdad carga el perfil prod, leído de application.properties y
 * application-prod.properties como al arrancar, sin base de datos ni red: el
 * pool se construye pero no conecta, y el correo no envía nada.
 *
 * Comprueba lo que el bloque de cierre puso en el perfil —sin el Detail de
 * PostgreSQL en el log (C6), TLS hasta la base de datos (D4), la estrategia
 * native de cabeceras (C1) y la comprobación de arranque de las variables de
 * correo—, y que el Dockerfile arranca con ese perfil.
 */
class PerfilProdTest {

    private static final String CLAVE = "re_clave_de_prueba_0123456789abcdef";

    /**
     * Las dos variables de correo que el perfil exige. Cada prueba pone las que
     * necesita: así la que falta, falta de verdad, y no llega vacía.
     */
    private static final String[] CORREO = {"RESEND_API_KEY=" + CLAVE, "MAIL_FROM=Animalia <avisos@animalia.test>"};

    /**
     * El perfil prod con lo mínimo para construir el pool —valores de prueba:
     * con ellos no se conecta a nada— y el correo de los dos perfiles, para ver
     * cuál se queda.
     */
    private final ApplicationContextRunner prod = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(ResendEmailSender.class, SmtpEmailSender.class)
            .withPropertyValues(
                    "spring.profiles.active=prod",
                    "DB_URL=jdbc:postgresql://localhost:1/prueba",
                    "DB_USER=prueba",
                    "DB_PASSWORD=prueba");

    @Test
    @DisplayName("El pool de prod lleva logServerErrorDetail=false (C6) y sslmode=require (D4)")
    void propiedadesDelDriver() {
        prod.withPropertyValues(CORREO).run(contexto -> {
            assertThat(contexto).hasNotFailed();
            HikariDataSource pool = contexto.getBean(HikariDataSource.class);
            assertThat(pool.getDataSourceProperties().getProperty("logServerErrorDetail")).isEqualTo("false");
            assertThat(pool.getDataSourceProperties().getProperty("sslmode")).isEqualTo("require");
        });
    }

    @Test
    @DisplayName("Prod usa la estrategia native de cabeceras, la que no se deja falsificar la IP (C1)")
    void estrategiaDeCabeceras() {
        prod.withPropertyValues(CORREO).run(contexto -> assertThat(
                contexto.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("native"));
    }

    @Test
    @DisplayName("Con las dos variables de correo, prod arranca con ResendEmailSender y sin SMTP")
    void correoPorResend() {
        prod.withPropertyValues(CORREO).run(contexto -> {
            assertThat(contexto).hasNotFailed();
            assertThat(contexto).hasSingleBean(EmailSender.class);
            assertThat(contexto.getBean(EmailSender.class)).isInstanceOf(ResendEmailSender.class);
        });
    }

    @Test
    @DisplayName("Sin RESEND_API_KEY —la variable no existe— prod no arranca, y el mensaje la nombra")
    void sinClaveNoArranca() {
        prod.withPropertyValues(CORREO[1]).run(contexto -> assertThat(contexto).hasFailed()
                .getFailure().rootCause().hasMessageContaining("RESEND_API_KEY"));
    }

    @Test
    @DisplayName("Sin MAIL_FROM tampoco, y el mensaje no enseña la clave que sí estaba")
    void sinRemitenteNoArranca() {
        prod.withPropertyValues(CORREO[0]).run(contexto -> assertThat(contexto).hasFailed()
                .getFailure().rootCause().hasMessageContaining("MAIL_FROM").hasMessageNotContaining(CLAVE));
    }

    @Test
    @DisplayName("El Dockerfile arranca la imagen con el perfil prod")
    void dockerfileConPerfilProd() throws Exception {
        assertThat(Files.readAllLines(Path.of("Dockerfile"), StandardCharsets.UTF_8))
                .contains("ENV SPRING_PROFILES_ACTIVE=prod");
    }
}
