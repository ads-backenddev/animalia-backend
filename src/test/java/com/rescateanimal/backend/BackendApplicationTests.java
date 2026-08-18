package com.rescateanimal.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Comprueba que el contexto de Spring arranca por completo: entidades, capas,
 * seguridad y el mapeo espacial de PostGIS.
 *
 * Necesita la base de datos levantada (docker compose up -d) y el perfil que
 * apunta a ella:
 *   .\mvnw.cmd test "-DargLine=-Dspring.profiles.active=local"
 *
 * El secreto de firma se fija aquí porque jwt.secret no tiene valor por defecto
 * en application.properties, a propósito, para que ningún despliegue arranque
 * con una clave publicada en el repositorio.
 */
@SpringBootTest(properties =
        "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
