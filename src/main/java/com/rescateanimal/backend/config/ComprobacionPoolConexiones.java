package com.rescateanimal.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Impide arrancar con tantos hilos de Tomcat como conexiones en el pool, o más,
 * y con los hilos virtuales activados.
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
 * LA CUENTA SOLO VALE SI A trasConfirmar LLEGAN ÚNICAMENTE HILOS DE TOMCAT, que
 * son los que acota server.tomcat.threads.max. Hoy es así: todos los avisos
 * salen de peticiones REST. Un hilo de otro origen que llegara a crear un aviso
 * tras el commit —un mensaje STOMP, que corre en el pool del clientInboundChannel;
 * una tarea @Async o @Scheduled— necesitaría también dos conexiones sin estar en
 * la cuenta, y habría que sumarlo a ella o darle su propio límite. Los que hoy
 * usan la base de datos fuera de Tomcat (la limpieza de fotos, los mensajes del
 * chat) toman una sola conexión y la sueltan: hacen esperar, pero no bloquean.
 *
 * POR ESO TAMPOCO ARRANCA CON spring.threads.virtual.enabled=true. Con hilos
 * virtuales Tomcat crea uno por petición y deja de aplicar threads.max: el tope
 * de peticiones a la vez desaparece, y con él la garantía. Activarlos exige
 * antes otro mecanismo, como limitar aparte cuántos avisos se crean a la vez.
 *
 * SE COMPRUEBA AL ARRANCAR, y no basta con el comentario, porque los valores se
 * cambian por separado (DB_POOL_MAX y TOMCAT_HILOS_MAX): subir uno sin el otro
 * volvería a abrir el agujero sin que nadie se enterase hasta el primer pico de
 * carga. Es preferible que no arranque, como sin jwt.secret.
 */
@Component
public class ComprobacionPoolConexiones {

    public ComprobacionPoolConexiones(
            @Value("${spring.datasource.hikari.maximum-pool-size}") int conexiones,
            @Value("${server.tomcat.threads.max}") int hilos,
            @Value("${spring.threads.virtual.enabled:false}") boolean hilosVirtuales) {
        if (hilosVirtuales) {
            throw new IllegalStateException("spring.threads.virtual.enabled=true no se admite: con hilos "
                    + "virtuales Tomcat deja de aplicar server.tomcat.threads.max, y sin ese tope las "
                    + "peticiones que crean un aviso tras el commit pueden agotar el pool de conexiones "
                    + "(ver ComprobacionPoolConexiones)");
        }
        if (hilos >= conexiones) {
            throw new IllegalStateException("server.tomcat.threads.max (" + hilos
                    + ") tiene que ser menor que spring.datasource.hikari.maximum-pool-size ("
                    + conexiones + "): cada petición que crea un aviso tras el commit necesita "
                    + "dos conexiones a la vez (ver application.properties)");
        }
    }
}
