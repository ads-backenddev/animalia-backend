package com.rescateanimal.backend.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Que sslmode, puesto como propiedad del driver en el pool —como lo pone el
 * perfil prod, spring.datasource.hikari.data-source-properties.sslmode—, de
 * verdad obliga a cifrar (informe ENS, D4).
 *
 * El PostgreSQL de desarrollo no tiene TLS (ssl = off), y eso es lo que hace
 * útil la prueba: con "require" el driver tiene que negarse a conectar, que es
 * lo que hará la aplicación en producción si la base de datos no cifra. Sin
 * sslmode, o con el "prefer" que el driver usa por defecto, conecta en claro.
 * Que prod lleva "require" lo comprueba PerfilProdTest.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class ConexionConTlsTest {

    @Autowired
    private Environment entorno;

    @Test
    @DisplayName("Con sslmode=require el driver no conecta con un PostgreSQL sin TLS: la conexión va cifrada o no hay conexión")
    void requireNoConectaSinTls() {
        // Por el SQLState y no por el mensaje, que el driver traduce al idioma de la JVM
        // ("The server does not support SSL", "Este servidor no soporta SSL").
        assertThatThrownBy(() -> pool("require").close())
                .rootCause()
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState())
                        .as("08004: el servidor rechaza la conexión").isEqualTo("08004"));
    }

    @Test
    @DisplayName("Con prefer, el valor por defecto del driver, la misma base de datos acepta la conexión en claro")
    void preferConectaEnClaro() throws Exception {
        try (HikariDataSource enClaro = pool("prefer"); Connection conexion = enClaro.getConnection()) {
            assertThat(conexion.isValid(2)).isTrue();
        }
    }

    /** Un pool como el de la aplicación, contra la misma base de datos, con ese sslmode. */
    private HikariDataSource pool(String sslmode) {
        HikariConfig configuracion = new HikariConfig();
        configuracion.setJdbcUrl(entorno.getRequiredProperty("spring.datasource.url"));
        configuracion.setUsername(entorno.getRequiredProperty("spring.datasource.username"));
        configuracion.setPassword(entorno.getRequiredProperty("spring.datasource.password"));
        configuracion.setMaximumPoolSize(1);
        configuracion.addDataSourceProperty("sslmode", sslmode);
        return new HikariDataSource(configuracion);
    }
}
