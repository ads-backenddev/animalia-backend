package com.rescateanimal.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Habilita @Async para el trabajo que no debe ocupar el hilo de la petición.
 *
 * Lo necesita el envío de correo (RF-17): si el email se enviara de forma
 * síncrona, la petición de recuperación tardaría bastante más cuando el email
 * existe que cuando no, y esa diferencia de tiempo permitiría deducir qué
 * cuentas están registradas por mucho que la respuesta sea siempre 204.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "tareasEnSegundoPlano")
    public TaskExecutor tareasEnSegundoPlano() {
        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(2);
        ejecutor.setMaxPoolSize(5);
        ejecutor.setQueueCapacity(50);
        ejecutor.setThreadNamePrefix("async-");
        ejecutor.initialize();
        return ejecutor;
    }
}
