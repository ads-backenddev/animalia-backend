package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cada valor de TipoNotificacion cabe en el CHECK de notificacion.tipo.
 *
 * ddl-auto=validate no compara CHECKs, así que una constante nueva en el enum
 * sin su migración arranca sin quejarse y solo falla al insertar la primera
 * notificación de ese tipo, en ejecución. Esta prueba lo adelanta: recorre el
 * enum entero, de modo que la próxima constante que llegue sin migración la
 * rompe sin que haya que acordarse de añadirla aquí.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class NotificacionTiposTest {

    @Autowired
    private NotificacionRepository notificacionRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;

    private Usuario destinatario;
    private final List<Long> creadas = new ArrayList<>();

    @BeforeEach
    void preparar() {
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Tipos");
        nuevo.setEmail("tipos-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        destinatario = usuarioRepository.save(nuevo);
    }

    @AfterEach
    void limpiar() {
        notificacionRepository.deleteAllById(creadas);
        usuarioRepository.delete(destinatario);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("La base de datos admite todos los tipos del enum")
    @EnumSource(TipoNotificacion.class)
    void todosLosTiposCabenEnElCheck(TipoNotificacion tipo) {
        Notificacion notificacion = new Notificacion();
        notificacion.setUsuario(destinatario);
        notificacion.setTipo(tipo);
        notificacion.setReferenciaId(1L);
        notificacion.setLeida(false);

        Notificacion guardada = notificacionRepository.saveAndFlush(notificacion);
        creadas.add(guardada.getId());

        assertThat(notificacionRepository.findById(guardada.getId()))
                .get().extracting(Notificacion::getTipo).isEqualTo(tipo);
    }
}
