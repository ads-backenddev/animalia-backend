package com.rescateanimal.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * El reloj del que salen los "hoy" de los tres cupos diarios —matches de
 * adopción, fotos y avistamientos por alerta—: el del sistema, en UTC.
 *
 * Es un bean para que las pruebas puedan fijar el instante y comprobar dónde
 * cambia el día. El día lo decide cada service con su zona, Europe/Madrid: el
 * reloj solo da el instante, y que vaya en UTC subraya que la zona de la JVM
 * no pinta nada.
 */
@Configuration
public class RelojConfig {

    @Bean
    public Clock reloj() {
        return Clock.systemUTC();
    }
}
