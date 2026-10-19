package com.rescateanimal.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita @Scheduled. Hoy lo usa solo la limpieza diaria de fotos
 * (LimpiezaFotosService).
 *
 * La purga de tokens revocados evitó a propósito añadir esto, y se hace al
 * revocar (TokenRevocadoService): su coste crece con los logouts y viaja bien
 * pegado a ellos. La limpieza de fotos no admite ese truco: encontrar archivos
 * sin fila obliga a recorrer la carpeta entera, y hacerlo en cada subida
 * costaría más cuantas más fotos hubiera. Es trabajo periódico por naturaleza.
 *
 * El cron vive en app.fotos.limpieza-cron, y las pruebas lo desactivan con "-"
 * para que nada se ejecute por su cuenta en mitad de una prueba.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
