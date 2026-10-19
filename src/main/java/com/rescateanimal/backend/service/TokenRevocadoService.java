package com.rescateanimal.backend.service;

import com.rescateanimal.backend.model.TokenRevocado;
import com.rescateanimal.backend.repository.TokenRevocadoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Registro de tokens invalidados antes de su caducidad natural.
 *
 * Cubre dos casos:
 * - el logout (RF-16), que revoca el token de acceso en curso,
 * - el restablecimiento de contraseña (RF-17), donde el token del enlace debe
 *   servir una sola vez.
 *
 * AHORA PERSISTE EN LA BASE DE DATOS, y ese es el cambio importante. Antes esto
 * era un ConcurrentHashMap y las revocaciones se perdían al reiniciar la
 * aplicación: un token del que alguien había hecho logout volvía a autenticar
 * durante lo que le quedara de sus 24 horas, y reiniciar no es un caso raro sino
 * cada despliegue. El razonamiento completo está en la migración V8.
 *
 * El contrato hacia fuera no ha cambiado: los dos métodos son los mismos, con la
 * misma firma y el mismo significado, así que AuthService, JwtAuthFilter y
 * JwtHandshakeInterceptor no se enteran. Lo que sí cambia es que ahora también
 * funciona con varias instancias de la aplicación, porque la lista deja de ser
 * de cada proceso.
 *
 * SE INDEXA POR EL jti Y NO POR EL TOKEN ENTERO: el jti no sirve para
 * autenticarse, de modo que ni esta tabla ni sus copias de seguridad contienen
 * credenciales utilizables.
 *
 * COSTE, QUE CONVIENE TENER PRESENTE: estaRevocado se llama en CADA petición
 * autenticada, así que ahora hay una consulta por petición donde antes había un
 * acceso a memoria. Es una búsqueda por clave primaria, que es lo más barato que
 * hace la base de datos, y a cambio la garantía es real. Si algún día el perfil
 * de carga lo pide, el sitio donde poner una caché es aquí dentro, sin tocar a
 * quien llama.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocadoService {

    private final TokenRevocadoRepository tokenRevocadoRepository;

    /**
     * Marca un token como no utilizable. Guardar su caducidad permite olvidarlo
     * cuando expire, de modo que la tabla no crece indefinidamente.
     *
     * Es idempotente: el jti es la clave primaria, así que revocar dos veces el
     * mismo token deja una sola fila.
     */
    @Transactional
    public void revocar(String jti, Instant expiracion) {
        if (jti == null) {
            return;
        }

        TokenRevocado revocado = new TokenRevocado();
        revocado.setJti(jti);
        revocado.setExpiraEn(expiracion);
        tokenRevocadoRepository.save(revocado);

        purgarCaducados();
    }

    @Transactional(readOnly = true)
    public boolean estaRevocado(String jti) {
        if (jti == null) {
            return false;
        }
        return tokenRevocadoRepository.existsByJtiAndExpiraEnAfter(jti, Instant.now());
    }

    /**
     * Elimina las filas de tokens que ya han caducado por sí solos.
     *
     * Se ejecuta al revocar en lugar de con una tarea programada, igual que
     * cuando esto vivía en memoria y por el mismo motivo: el coste es
     * proporcional al número de logouts, y evita añadir @EnableScheduling solo
     * para esto. Una fila caducada no es peligrosa —el token ya no vale por su
     * propia firma—, solo es basura.
     */
    private void purgarCaducados() {
        int purgados = tokenRevocadoRepository.purgarCaducadosAntesDe(Instant.now());
        if (purgados > 0) {
            log.debug("Purgados {} tokens revocados ya caducados", purgados);
        }
    }
}
