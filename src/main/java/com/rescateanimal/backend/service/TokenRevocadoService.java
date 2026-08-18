package com.rescateanimal.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro de tokens invalidados antes de su caducidad natural.
 *
 * Cubre dos casos:
 * - el logout (RF-16), que revoca el token de acceso en curso,
 * - el restablecimiento de contraseña (RF-17), donde el token del enlace debe
 *   servir una sola vez.
 *
 * Se indexa por el jti del token, no por el token entero, para no guardar
 * credenciales completas en memoria.
 *
 * LIMITACIÓN CONOCIDA: el almacenamiento es en memoria, así que las revocaciones
 * se pierden al reiniciar y no se comparten entre varias instancias de la
 * aplicación. Es suficiente para el despliegue de una sola instancia previsto;
 * si se escala horizontalmente habrá que sustituirlo por Redis o una tabla, sin
 * cambiar esta interfaz.
 */
@Slf4j
@Service
public class TokenRevocadoService {

    /** jti -> instante en que el token caduca por sí solo y ya no hace falta recordarlo. */
    private final Map<String, Instant> revocados = new ConcurrentHashMap<>();

    /**
     * Marca un token como no utilizable. Guardar su caducidad permite olvidarlo
     * cuando expire, de modo que el mapa no crece indefinidamente.
     */
    public void revocar(String jti, Instant expiracion) {
        if (jti == null) {
            return;
        }
        revocados.put(jti, expiracion);
        purgarCaducados();
    }

    public boolean estaRevocado(String jti) {
        if (jti == null) {
            return false;
        }
        Instant expiracion = revocados.get(jti);
        if (expiracion == null) {
            return false;
        }
        // Si ya caducó por sí solo, el token es invalido de todas formas y se
        // deja de rastrear.
        if (expiracion.isBefore(Instant.now())) {
            revocados.remove(jti);
            return false;
        }
        return true;
    }

    /**
     * Elimina las entradas que ya han caducado. Se ejecuta al revocar en lugar de
     * con una tarea programada: el coste es proporcional al número de logouts, y
     * evita añadir @EnableScheduling solo para esto.
     */
    private void purgarCaducados() {
        Instant ahora = Instant.now();
        int antes = revocados.size();
        revocados.entrySet().removeIf(entrada -> entrada.getValue().isBefore(ahora));
        int purgados = antes - revocados.size();
        if (purgados > 0) {
            log.debug("Purgados {} tokens revocados ya caducados", purgados);
        }
    }
}
