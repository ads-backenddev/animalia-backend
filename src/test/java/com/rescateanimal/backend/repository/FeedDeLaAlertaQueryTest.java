package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.dto.response.AvistamientoFeedResponse;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.service.AvistamientoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El feed de avistamientos de una alerta contra PostgreSQL de verdad:
 * AvistamientoRepository.buscarPendientesDeRevisar y distanciasALaAlerta, a
 * través de AvistamientoService.feedDeLaAlerta.
 *
 * Lo que solo se sabe ejecutándolo: que el NOT EXISTS sobre MATCH_REGISTRO
 * filtra por quién deslizó y por el tipo, y no solo por el id —la referencia es
 * polimórfica—, y que ST_Distance sobre geography da metros.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class FeedDeLaAlertaQueryTest {

    /** 0,01º de latitud son unos 1.111 m. */
    private static final double LAT = 40.30;
    private static final double LNG = -3.70;

    private final GeometryFactory geometrias = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private AvistamientoService avistamientoService;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private AvistamientoRepository avistamientoRepository;
    @Autowired
    private MatchRegistroRepository matchRegistroRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private Usuario dueno;
    private Usuario testigo;
    private Usuario otro;
    private Alerta alerta;
    private final List<Long> alertas = new ArrayList<>();
    private final List<Long> avistamientos = new ArrayList<>();
    private final List<Long> deslizamientos = new ArrayList<>();
    private final List<Usuario> usuarios = new ArrayList<>();

    @BeforeEach
    void preparar() {
        dueno = usuario("Dueño");
        testigo = usuario("Testigo");
        otro = usuario("Otro");
        alerta = alerta(dueno);
    }

    @AfterEach
    void limpiar() {
        matchRegistroRepository.deleteAllById(deslizamientos);
        avistamientoRepository.deleteAllById(avistamientos);
        alertaRepository.deleteAllById(alertas);
        usuarioRepository.deleteAll(usuarios);
    }

    @Test
    @DisplayName("Solo los VISIBLE de su alerta que el dueño no ha deslizado, sin los suyos, más recientes primero")
    void contenidoYOrden() {
        long reciente = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 1, true);
        long intermedio = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 2, true);
        long antiguo = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 3, true);

        long descartado = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 4, true);
        deslizar(dueno, descartado, TipoPublicacion.AVISTAMIENTO, false);
        long conMatch = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 5, true);
        deslizar(dueno, conMatch, TipoPublicacion.AVISTAMIENTO, true);
        avistamiento(alerta, testigo, EstadoAvistamiento.ELIMINADO, 6, true);
        avistamiento(alerta, dueno, EstadoAvistamiento.VISIBLE, 7, true);
        avistamiento(alerta(dueno), testigo, EstadoAvistamiento.VISIBLE, 8, true);

        // Lo que deslizan otros no cuenta: revisar es cosa del dueño.
        deslizar(otro, intermedio, TipoPublicacion.AVISTAMIENTO, true);
        // La referencia es polimórfica: un swipe del dueño sobre el ANIMAL con
        // el mismo id no tiene nada que ver con este avistamiento.
        deslizar(dueno, antiguo, TipoPublicacion.ADOPCION, false);

        assertThat(ids(feed())).containsExactly(reciente, intermedio, antiguo);
    }

    @Test
    @DisplayName("Dos del mismo instante salen por id descendente, siempre en el mismo orden")
    void desempatePorId() {
        long primero = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 1, true);
        long segundo = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 1, true);
        jdbc.update("UPDATE avistamiento SET created_at = (SELECT created_at FROM avistamiento WHERE id = ?) "
                + "WHERE id = ?", primero, segundo);

        assertThat(ids(feed())).containsExactly(segundo, primero);
    }

    @Test
    @DisplayName("La distancia a la alerta son metros reales sobre geography, y null sin ubicación")
    void distancia() {
        long cerca = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 1, true);
        long sinUbicacion = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 2, false);

        List<AvistamientoFeedResponse> feed = feed();

        assertThat(ids(feed)).containsExactly(cerca, sinUbicacion);
        assertThat(feed.get(0).getDistanciaMetros()).isBetween(1_105L, 1_115L);
        assertThat(feed.get(1).getDistanciaMetros()).isNull();
    }

    // ---------------------------------------------------------------------

    private List<AvistamientoFeedResponse> feed() {
        return avistamientoService.feedDeLaAlerta(alerta.getId(), dueno.getId());
    }

    private static List<Long> ids(List<AvistamientoFeedResponse> feed) {
        return feed.stream().map(AvistamientoFeedResponse::getId).toList();
    }

    private Usuario usuario(String nombre) {
        Usuario nuevo = new Usuario();
        nuevo.setNombre(nombre);
        nuevo.setEmail("feed-alerta-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        Usuario guardado = usuarioRepository.save(nuevo);
        usuarios.add(guardado);
        return guardado;
    }

    private Alerta alerta(Usuario autor) {
        Alerta nueva = new Alerta();
        nueva.setUsuario(autor);
        nueva.setDescripcion("Para el feed del dueño");
        nueva.setEspecie("feed-" + UUID.randomUUID().toString().substring(0, 8));
        nueva.setFechaAvistamiento(Instant.now());
        nueva.setUltimaUbicacion(punto(LAT));
        nueva.setEstado(EstadoAlerta.ACTIVA);
        Alerta guardada = alertaRepository.save(nueva);
        alertas.add(guardada.getId());
        return guardada;
    }

    /** Un avistamiento de hace tantas horas, a 0,01º al norte de la alerta o sin ubicación. */
    private long avistamiento(Alerta deLaAlerta, Usuario autor, EstadoAvistamiento estado,
                              int horasDeAntiguedad, boolean conUbicacion) {
        Avistamiento nuevo = new Avistamiento();
        nuevo.setAlerta(deLaAlerta);
        nuevo.setUsuario(autor);
        nuevo.setDescripcion("Visto cerca");
        nuevo.setUbicacion(conUbicacion ? punto(LAT + 0.01) : null);
        nuevo.setEstado(estado);
        Long id = avistamientoRepository.save(nuevo).getId();
        avistamientos.add(id);
        jdbc.update("UPDATE avistamiento SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(3_600L * horasDeAntiguedad)), id);
        return id;
    }

    private void deslizar(Usuario quien, long publicacionId, TipoPublicacion tipo, boolean interesado) {
        MatchRegistro registro = new MatchRegistro();
        registro.setUsuario(quien);
        registro.setPublicacionId(publicacionId);
        registro.setTipoPublicacion(tipo);
        registro.setInteresado(interesado);
        deslizamientos.add(matchRegistroRepository.save(registro).getId());
    }

    private Point punto(double latitud) {
        return geometrias.createPoint(new Coordinate(LNG, latitud));
    }
}
