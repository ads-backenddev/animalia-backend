package com.rescateanimal.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La regla que impide agotar el pool con los avisos tras el commit: menos hilos
 * de Tomcat que conexiones. Que la regla es la buena lo demuestra
 * PoolDeConexionesTest contra PostgreSQL; aquí, solo que se aplica.
 */
class ComprobacionPoolConexionesTest {

    @Test
    @DisplayName("Con un hilo menos que conexiones arranca: son los valores por defecto")
    void unHiloMenosArranca() {
        assertThatCode(() -> new ComprobacionPoolConexiones(20, 19)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Con tantos hilos como conexiones no arranca: es justo el caso que agota el pool")
    void tantosHilosComoConexionesNoArranca() {
        assertThatThrownBy(() -> new ComprobacionPoolConexiones(20, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.tomcat.threads.max (20)")
                .hasMessageContaining("maximum-pool-size (20)");
    }

    @Test
    @DisplayName("Con más hilos que conexiones tampoco: los 200 de Tomcat por defecto contra el pool")
    void masHilosQueConexionesNoArranca() {
        assertThatThrownBy(() -> new ComprobacionPoolConexiones(20, 200))
                .isInstanceOf(IllegalStateException.class);
    }
}
