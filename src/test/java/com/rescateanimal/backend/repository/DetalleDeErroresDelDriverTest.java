package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El driver no pone en sus mensajes el "Detail" de PostgreSQL, que lleva los
 * valores de la fila que choca (logServerErrorDetail=false en
 * application.properties; informe ENS, C6).
 *
 * Se comprueba con un email duplicado porque es el caso que motivó el cambio:
 * el mensaje es el que GlobalExceptionHandler escribe en el log. Y se
 * comprueba también lo que tiene que seguir ahí: el nombre de la restricción,
 * del que depende reconocer un doble swipe (MatchService).
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class DetalleDeErroresDelDriverTest {

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final String email = "detalle-" + UUID.randomUUID() + "@test.es";

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM usuario WHERE lower(email) = lower(?)", email);
    }

    @Test
    @DisplayName("Un email duplicado deja en el mensaje el nombre de la restricción, pero no el email")
    void elMensajeNoLlevaElDetalle() {
        usuarioRepository.save(usuario(email));

        assertThatThrownBy(() -> usuarioRepository.save(usuario(email.toUpperCase())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(((DataIntegrityViolationException) e).getMostSpecificCause().getMessage())
                        .contains("uk_usuario_email_lower")
                        .doesNotContainIgnoringCase(email)
                        .doesNotContain("Detail"));
    }

    private static Usuario usuario(String email) {
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Detalle");
        nuevo.setEmail(email);
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        return nuevo;
    }
}
