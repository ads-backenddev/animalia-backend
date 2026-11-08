package com.rescateanimal.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La regla que impide agotar el pool con los avisos tras el commit: menos hilos
 * de Tomcat que conexiones, y sin hilos virtuales. Que la regla es la buena lo
 * demuestra PoolDeConexionesTest contra PostgreSQL; aquí, solo que se aplica.
 */
class ComprobacionPoolConexionesTest {

    /** Solo la comprobación, con los valores por defecto de application.properties. */
    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(ComprobacionPoolConexiones.class)
            .withPropertyValues("spring.datasource.hikari.maximum-pool-size=20", "server.tomcat.threads.max=19");

    @Test
    @DisplayName("Con un hilo menos que conexiones arranca: son los valores por defecto")
    void unHiloMenosArranca() {
        assertThatCode(() -> new ComprobacionPoolConexiones(20, 19, false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Con tantos hilos como conexiones no arranca: es justo el caso que agota el pool")
    void tantosHilosComoConexionesNoArranca() {
        assertThatThrownBy(() -> new ComprobacionPoolConexiones(20, 20, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.tomcat.threads.max (20)")
                .hasMessageContaining("maximum-pool-size (20)");
    }

    @Test
    @DisplayName("Con más hilos que conexiones tampoco: los 200 de Tomcat por defecto contra el pool")
    void masHilosQueConexionesNoArranca() {
        assertThatThrownBy(() -> new ComprobacionPoolConexiones(20, 200, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Con hilos virtuales no arranca, aunque los números cuadren: Tomcat deja de aplicar threads.max")
    void conHilosVirtualesNoArranca() {
        assertThatThrownBy(() -> new ComprobacionPoolConexiones(20, 19, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.threads.virtual.enabled=true");
    }

    @Test
    @DisplayName("La propiedad que se lee es la de Spring Boot: con spring.threads.virtual.enabled=true el contexto no arranca")
    void elContextoLeeLaPropiedadDeLosHilosVirtuales() {
        contexto.run(arrancado -> assertThat(arrancado).hasNotFailed());
        contexto.withPropertyValues("spring.threads.virtual.enabled=true")
                .run(arrancado -> assertThat(arrancado).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("spring.threads.virtual.enabled=true"));
    }
}
