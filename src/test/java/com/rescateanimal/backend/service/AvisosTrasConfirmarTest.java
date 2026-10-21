package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearAlertaRequest;
import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.request.CrearMatchRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los avisos se crean DESPUÉS del commit de la operación que los origina, con
 * transacciones de verdad en PostgreSQL.
 *
 * Lo que solo se ve así: que dentro de la transacción de la operación el aviso
 * todavía no existe —ni siquiera para su propia conexión—, que aparece en
 * cuanto confirma, y que si la operación se deshace no queda ninguno apuntando
 * a algo que nunca existió.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class AvisosTrasConfirmarTest {

    private static final double LAT = 40.35;
    private static final double LNG = -3.75;

    private final GeometryFactory geometrias = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private AvistamientoService avistamientoService;
    @Autowired
    private AlertaService alertaService;
    @Autowired
    private MatchService matchService;
    @Autowired
    private AdminService adminService;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private FotoRepository fotoRepository;
    @Autowired
    private AlmacenFotosService almacenFotos;
    @Autowired
    private PlatformTransactionManager transacciones;
    @Autowired
    private JdbcTemplate jdbc;

    private Usuario dueno;
    private Usuario testigo;
    private String fotoDelTestigo;
    private Long alertaId;
    private final List<Long> alertasPublicadas = new ArrayList<>();
    private final List<Usuario> usuarios = new ArrayList<>();

    @BeforeEach
    void preparar() {
        dueno = usuario("Dueño");
        testigo = usuario("Testigo");

        // registrar exige que la foto sea del testigo; para eso basta la fila.
        Foto foto = new Foto();
        foto.setNombre(UUID.randomUUID() + ".jpg");
        foto.setUsuario(testigo);
        foto.setTamanyo(1);
        fotoRepository.save(foto);
        fotoDelTestigo = almacenFotos.urlDe(foto.getNombre());

        Alerta alerta = new Alerta();
        alerta.setUsuario(dueno);
        alerta.setDescripcion("Para los avisos");
        alerta.setEspecie("avisos-" + UUID.randomUUID().toString().substring(0, 8));
        alerta.setFechaAvistamiento(Instant.now());
        alerta.setUltimaUbicacion(geometrias.createPoint(new Coordinate(LNG, LAT)));
        alerta.setEstado(EstadoAlerta.ACTIVA);
        alertaId = alertaRepository.save(alerta).getId();
    }

    @AfterEach
    void limpiar() {
        for (Usuario usuario : usuarios) {
            jdbc.update("DELETE FROM chat WHERE match_id IN "
                    + "(SELECT id FROM match_registro WHERE usuario_id = ?)", usuario.getId());
            jdbc.update("DELETE FROM match_registro WHERE usuario_id = ?", usuario.getId());
            jdbc.update("DELETE FROM notificacion WHERE usuario_id = ?", usuario.getId());
        }
        jdbc.update("DELETE FROM avistamiento WHERE alerta_id = ?", alertaId);
        alertaRepository.deleteById(alertaId);
        alertaRepository.deleteAllById(alertasPublicadas);
        fotoRepository.deleteById(almacenFotos.nombreDe(fotoDelTestigo).orElseThrow());
        usuarioRepository.deleteAll(usuarios);
    }

    @Test
    @DisplayName("NUEVO_AVISTAMIENTO: no existe dentro de la transacción, aparece al confirmar y apunta a la alerta")
    void nuevoAvistamientoTrasElCommit() {
        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            avistar();
            assertThat(avisos(dueno, "NUEVO_AVISTAMIENTO"))
                    .as("dentro de la transacción del avistamiento, todavía nada").isEmpty();
        });

        assertThat(avisos(dueno, "NUEVO_AVISTAMIENTO")).containsExactly(alertaId);
    }

    @Test
    @DisplayName("NUEVO_AVISTAMIENTO: si el avistamiento se deshace, no queda aviso")
    void nuevoAvistamientoSinCommit() {
        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            avistar();
            estado.setRollbackOnly();
        });

        assertThat(avisos(dueno, "NUEVO_AVISTAMIENTO")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM avistamiento WHERE alerta_id = ?",
                Integer.class, alertaId)).isZero();
    }

    @Test
    @DisplayName("ALERTA_ENCONTRADA: el testigo con chat lo recibe al confirmarse el cierre, y una sola vez")
    void alertaEncontradaTrasElCommit() {
        conChatConElTestigo();

        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            alertaService.cerrar(alertaId, dueno.getId());
            assertThat(avisos(testigo, "ALERTA_ENCONTRADA"))
                    .as("dentro de la transacción del cierre, todavía nada").isEmpty();
        });
        assertThat(avisos(testigo, "ALERTA_ENCONTRADA")).containsExactly(alertaId);
        assertThat(avisos(dueno, "ALERTA_ENCONTRADA")).as("quien cierra no se avisa").isEmpty();

        // Repetir el cierre no cambia nada, y tampoco vuelve a avisar.
        alertaService.cerrar(alertaId, dueno.getId());
        assertThat(avisos(testigo, "ALERTA_ENCONTRADA")).hasSize(1);
    }

    @Test
    @DisplayName("ALERTA_ENCONTRADA: si el cierre se deshace, no hay aviso y la alerta sigue ACTIVA")
    void alertaEncontradaSinCommit() {
        conChatConElTestigo();

        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            alertaService.cerrar(alertaId, dueno.getId());
            estado.setRollbackOnly();
        });

        assertThat(avisos(testigo, "ALERTA_ENCONTRADA")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT estado FROM alerta WHERE id = ?", String.class, alertaId))
                .isEqualTo("ACTIVA");
    }

    @Test
    @DisplayName("ALERTA_CERCANA: la zona se entera cuando la alerta está confirmada, nunca antes")
    void alertaCercanaTrasElCommit() {
        Usuario vecino = vecinoDelDueno();

        Long publicada = new TransactionTemplate(transacciones).execute(estado -> {
            Long id = alertaService.publicar(peticionDeAlerta(), dueno.getId()).getId();
            assertThat(avisos(vecino, "ALERTA_CERCANA"))
                    .as("dentro de la transacción de la publicación, todavía nada").isEmpty();
            return id;
        });
        alertasPublicadas.add(publicada);

        assertThat(avisos(vecino, "ALERTA_CERCANA")).containsExactly(publicada);
    }

    @Test
    @DisplayName("ALERTA_CERCANA: si la publicación se deshace, no queda un aviso huérfano")
    void alertaCercanaSinCommit() {
        Usuario vecino = vecinoDelDueno();

        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            alertaService.publicar(peticionDeAlerta(), dueno.getId());
            estado.setRollbackOnly();
        });

        assertThat(avisos(vecino, "ALERTA_CERCANA")).isEmpty();
    }

    @Test
    @DisplayName("Decisión del administrador: el aviso llega con la decisión confirmada")
    void decisionAdministrativaTrasElCommit() {
        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            adminService.bloquear(testigo.getId(), "Spam", dueno.getId());
            assertThat(cuantos(testigo, "CUENTA_BLOQUEADA"))
                    .as("dentro de la transacción del bloqueo, todavía nada").isZero();
        });

        assertThat(cuantos(testigo, "CUENTA_BLOQUEADA")).isEqualTo(1);
    }

    @Test
    @DisplayName("Decisión del administrador: si se deshace, no hay aviso de algo que no pasó")
    void decisionAdministrativaSinCommit() {
        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            adminService.bloquear(testigo.getId(), "Spam", dueno.getId());
            estado.setRollbackOnly();
        });

        assertThat(cuantos(testigo, "CUENTA_BLOQUEADA")).isZero();
        assertThat(usuarioRepository.findById(testigo.getId()).orElseThrow().getActivo()).isTrue();
    }

    // ---------------------------------------------------------------------

    /** Dueño y vecino comparten un área que solo es de esta prueba. */
    private Usuario vecinoDelDueno() {
        String area = "zona-" + UUID.randomUUID().toString().substring(0, 8);
        dueno.setAreaGeografica(area);
        dueno = usuarioRepository.save(dueno);
        Usuario vecino = usuario("Vecino");
        vecino.setAreaGeografica(area);
        return usuarioRepository.save(vecino);
    }

    private CrearAlertaRequest peticionDeAlerta() {
        return new CrearAlertaRequest("Perdido en la zona", null,
                "zona-" + UUID.randomUUID().toString().substring(0, 8), Instant.now(),
                new UbicacionRequest(LAT, LNG), null);
    }

    private int cuantos(Usuario destinatario, String tipo) {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM notificacion WHERE usuario_id = ? AND tipo = ?",
                Integer.class, destinatario.getId(), tipo);
        return total == null ? 0 : total;
    }

    /** El testigo registra un avistamiento y el dueño hace match con él: hay chat. */
    private void conChatConElTestigo() {
        avistar();
        Long avistamiento = jdbc.queryForObject(
                "SELECT id FROM avistamiento WHERE alerta_id = ?", Long.class, alertaId);
        matchService.deslizar(new CrearMatchRequest(avistamiento, TipoPublicacion.AVISTAMIENTO, true),
                dueno.getId());
    }

    private void avistar() {
        avistamientoService.registrar(new CrearAvistamientoRequest(alertaId, "Visto junto al río",
                fotoDelTestigo, new UbicacionRequest(LAT, LNG)), testigo.getId());
    }

    /** Las referencias de los avisos de ese tipo que tiene el usuario. */
    private List<Long> avisos(Usuario destinatario, String tipo) {
        return jdbc.queryForList("SELECT referencia_id FROM notificacion WHERE usuario_id = ? AND tipo = ?",
                Long.class, destinatario.getId(), tipo);
    }

    private Usuario usuario(String nombre) {
        Usuario nuevo = new Usuario();
        nuevo.setNombre(nombre);
        nuevo.setEmail("avisos-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        Usuario guardado = usuarioRepository.save(nuevo);
        usuarios.add(guardado);
        return guardado;
    }
}
