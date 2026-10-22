package com.rescateanimal.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Impide arrancar con tantos hilos de Tomcat como conexiones en el pool, o más.
 *
 * Un aviso que se crea tras el commit (NotificacionService.trasConfirmar) pide
 * una segunda conexión mientras la petición conserva la primera. Si todas las
 * conexiones las tuvieran peticiones en ese punto, ninguna conseguiría la
 * segunda: esperarían todas el connectionTimeout de Hikari, sus avisos se
 * perderían y el resto de la API se quedaría sin conexiones mientras tanto. Con
 * menos hilos que conexiones no puede pasar: siempre queda una libre o en manos
 * de quien no necesita otra. Lo demuestra PoolDeConexionesTest, y los valores
 * se justifican en application.properties.
 *
 * SE COMPRUEBA AL ARRANCAR, y no basta con el comentario, porque los dos
 * valores se cambian por separado (DB_POOL_MAX y TOMCAT_HILOS_MAX): subir uno
 * sin el otro volvería a abrir el agujero sin que nadie se enterase hasta el
 * primer pico de carga. Es preferible que no arranque, como sin jwt.secret.
 */
@Component
public class ComprobacionPoolConexiones {

    public ComprobacionPoolConexiones(
            @Value("${spring.datasource.hikari.maximum-pool-size}") int conexiones,
            @Value("${server.tomcat.threads.max}") int hilos) {
        if (hilos >= conexiones) {
            throw new IllegalStateException("server.tomcat.threads.max (" + hilos
                    + ") tiene que ser menor que spring.datasource.hikari.maximum-pool-size ("
                    + conexiones + "): cada petición que crea un aviso tras el commit necesita "
                    + "dos conexiones a la vez (ver application.properties)");
        }
    }
}
