package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.dto.response.NotificacionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.service.NotificacionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GET /api/notificaciones y su marcado contra PostgreSQL de verdad, a través de
 * NotificacionService: que cada usuario ve solo las suyas, en qué orden, con qué
 * filtro y con qué tope de página.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class NotificacionesQueryTest {

    @Autowired
    private NotificacionService notificacionService;
    @Autowired
    private NotificacionRepository notificacionRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private Usuario ana;
    private Usuario luis;

    @BeforeEach
    void preparar() {
        ana = usuario();
        luis = usuario();
    }

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM notificacion WHERE usuario_id IN (?, ?)", ana.getId(), luis.getId());
        usuarioRepository.deleteAll(List.of(ana, luis));
    }

    @Test
    @DisplayName("Solo las propias, más recientes primero y con el id como desempate")
    void soloLasPropiasYEnOrden() {
        long antigua = aviso(ana, false, 3);
        long reciente = aviso(ana, false, 1);
        long empateA = aviso(ana, true, 2);
        long empateB = aviso(ana, true, 2);
        jdbc.update("UPDATE notificacion SET created_at = (SELECT created_at FROM notificacion WHERE id = ?) "
                + "WHERE id = ?", empateA, empateB);
        aviso(luis, false, 0);

        // Un orden pedido por el cliente no cambia nada: el listado tiene el suyo.
        PaginaResponse<NotificacionResponse> pagina = notificacionService.listar(ana.getId(), null,
                PageRequest.of(0, 20, Sort.by("id")));

        assertThat(ids(pagina)).containsExactly(reciente, empateB, empateA, antigua);
        assertThat(pagina.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("leida=false da las pendientes y leida=true las leídas")
    void filtroLeida() {
        long pendiente = aviso(ana, false, 1);
        long leida = aviso(ana, true, 2);

        assertThat(ids(notificacionService.listar(ana.getId(), false, PageRequest.of(0, 20))))
                .containsExactly(pendiente);
        assertThat(ids(notificacionService.listar(ana.getId(), true, PageRequest.of(0, 20))))
                .containsExactly(leida);
    }

    @Test
    @DisplayName("La página no pasa de 100")
    void topeDePagina() {
        assertThat(notificacionService.listar(ana.getId(), null, PageRequest.of(0, 500)).getSize())
                .isEqualTo(100);
    }

    @Test
    @DisplayName("Marcar una propia la deja leída, y repetirlo no cambia nada")
    void marcarPropia() {
        long id = aviso(ana, false, 1);

        notificacionService.marcarLeida(id, ana.getId());
        notificacionService.marcarLeida(id, ana.getId());

        assertThat(notificacionRepository.findById(id)).get()
                .extracting(Notificacion::getLeida).isEqualTo(true);
    }

    @Test
    @DisplayName("Marcar una ajena es 403 y no la toca")
    void marcarAjena() {
        long id = aviso(luis, false, 1);

        assertThatThrownBy(() -> notificacionService.marcarLeida(id, ana.getId()))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(notificacionRepository.findById(id)).get()
                .extracting(Notificacion::getLeida).isEqualTo(false);
    }

    // ---------------------------------------------------------------------

    private static List<Long> ids(PaginaResponse<NotificacionResponse> pagina) {
        return pagina.getContent().stream().map(NotificacionResponse::getId).toList();
    }

    /** Un aviso de hace tantas horas, leído o no. */
    private long aviso(Usuario destinatario, boolean leida, int horasDeAntiguedad) {
        Notificacion nueva = new Notificacion();
        nueva.setUsuario(destinatario);
        nueva.setTipo(TipoNotificacion.NUEVO_AVISTAMIENTO);
        nueva.setReferenciaId(1L);
        nueva.setLeida(leida);
        Long id = notificacionRepository.save(nueva).getId();
        jdbc.update("UPDATE notificacion SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(3_600L * horasDeAntiguedad)), id);
        return id;
    }

    private Usuario usuario() {
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Avisos");
        nuevo.setEmail("notificaciones-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        return usuarioRepository.save(nuevo);
    }
}
