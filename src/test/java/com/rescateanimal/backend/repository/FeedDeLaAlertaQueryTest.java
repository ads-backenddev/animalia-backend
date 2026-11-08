package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.dto.response.AvistamientoFeedResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El feed de avistamientos de una alerta, y la lista de los que su dueño
 * descartó, contra PostgreSQL de verdad: buscarPendientesDeRevisar,
 * buscarDescartados y distanciasALaAlerta de AvistamientoRepository, a través
 * de AvistamientoService.
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
    @DisplayName("Sin los avistamientos de cuentas bloqueadas o dadas de baja: el match con ellos se rechaza")
    void sinCuentasInactivas() {
        long deUnaCuentaActiva = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 1, true);
        avistamiento(alerta, otro, EstadoAvistamiento.VISIBLE, 2, true);
        // Bloquear y dar de baja dejan los dos activo = false; para el feed es lo mismo.
        otro.setActivo(false);
        usuarioRepository.save(otro);

        assertThat(ids(feed())).containsExactly(deUnaCuentaActiva);
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

    @Test
    @DisplayName("Descartados: solo lo que descartó el dueño, VISIBLE y de cuentas activas, el último descarte primero")
    void descartadosContenidoYOrden() {
        long antiguo = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 1, true);
        long reciente = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 2, true);
        // El orden es el del descarte, no el del avistamiento: el más antiguo
        // de los dos se descartó el último.
        descartarHace(dueno, reciente, 3);
        descartarHace(dueno, antiguo, 1);

        long conMatch = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 3, true);
        deslizar(dueno, conMatch, TipoPublicacion.AVISTAMIENTO, true);
        long descartadoPorOtro = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 4, true);
        deslizar(otro, descartadoPorOtro, TipoPublicacion.AVISTAMIENTO, false);
        long retirado = avistamiento(alerta, testigo, EstadoAvistamiento.ELIMINADO, 5, true);
        deslizar(dueno, retirado, TipoPublicacion.AVISTAMIENTO, false);
        Usuario inactivo = usuario("Inactivo");
        long deUnaCuentaInactiva = avistamiento(alerta, inactivo, EstadoAvistamiento.VISIBLE, 6, true);
        deslizar(dueno, deUnaCuentaInactiva, TipoPublicacion.AVISTAMIENTO, false);
        inactivo.setActivo(false);
        usuarioRepository.save(inactivo);
        long deOtraAlerta = avistamiento(alerta(dueno), testigo, EstadoAvistamiento.VISIBLE, 7, true);
        deslizar(dueno, deOtraAlerta, TipoPublicacion.AVISTAMIENTO, false);
        // La referencia es polimórfica: descartar el ANIMAL con el mismo id que
        // este avistamiento no lo convierte en un avistamiento descartado.
        long sinDescartar = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 8, true);
        deslizar(dueno, sinDescartar, TipoPublicacion.ADOPCION, false);

        assertThat(ids(descartados(0, 20).getContent())).containsExactly(antiguo, reciente);
    }

    @Test
    @DisplayName("Descartados: dos descartes del mismo instante salen por el id del registro, el último primero")
    void descartadosDesempatePorIdDelRegistro() {
        long antiguo = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 2, true);
        long reciente = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, 1, true);
        // El reciente se descarta antes, así que su registro tiene el id menor:
        // el orden de los registros es el contrario al de los avistamientos, y
        // la prueba distingue cuál de los dos desempata.
        long primerDescarte = deslizar(dueno, reciente, TipoPublicacion.AVISTAMIENTO, false);
        long segundoDescarte = deslizar(dueno, antiguo, TipoPublicacion.AVISTAMIENTO, false);
        jdbc.update("UPDATE match_registro SET created_at = (SELECT created_at FROM match_registro WHERE id = ?) "
                + "WHERE id = ?", primerDescarte, segundoDescarte);

        assertThat(ids(descartados(0, 20).getContent())).containsExactly(antiguo, reciente);
    }

    @Test
    @DisplayName("Descartados en páginas, con el total")
    void descartadosEnPaginas() {
        List<Long> enOrden = new ArrayList<>();
        for (int horas = 1; horas <= 3; horas++) {
            long descartado = avistamiento(alerta, testigo, EstadoAvistamiento.VISIBLE, horas, true);
            descartarHace(dueno, descartado, horas);
            enOrden.add(descartado);
        }

        PaginaResponse<AvistamientoFeedResponse> primera = descartados(0, 2);
        PaginaResponse<AvistamientoFeedResponse> segunda = descartados(1, 2);

        assertThat(ids(primera.getContent())).containsExactly(enOrden.get(0), enOrden.get(1));
        assertThat(ids(segunda.getContent())).containsExactly(enOrden.get(2));
        assertThat(primera.getTotalElements()).isEqualTo(3);
        assertThat(primera.getTotalPages()).isEqualTo(2);
        assertThat(segunda.isLast()).isTrue();
    }

    // ---------------------------------------------------------------------

    private List<AvistamientoFeedResponse> feed() {
        return avistamientoService.feedDeLaAlerta(alerta.getId(), dueno.getId());
    }

    private PaginaResponse<AvistamientoFeedResponse> descartados(int pagina, int tamanyo) {
        return avistamientoService.descartadosDeLaAlerta(alerta.getId(), dueno.getId(),
                PageRequest.of(pagina, tamanyo));
    }

    /** Un descarte de quien, hecho hace tantas horas. */
    private void descartarHace(Usuario quien, long avistamiento, int horas) {
        long registro = deslizar(quien, avistamiento, TipoPublicacion.AVISTAMIENTO, false);
        jdbc.update("UPDATE match_registro SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(3_600L * horas)), registro);
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

    private long deslizar(Usuario quien, long publicacionId, TipoPublicacion tipo, boolean interesado) {
        MatchRegistro registro = new MatchRegistro();
        registro.setUsuario(quien);
        registro.setPublicacionId(publicacionId);
        registro.setTipoPublicacion(tipo);
        registro.setInteresado(interesado);
        Long id = matchRegistroRepository.save(registro).getId();
        deslizamientos.add(id);
        return id;
    }

    private Point punto(double latitud) {
        return geometrias.createPoint(new Coordinate(LNG, latitud));
    }
}
