package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.dto.response.AlertaResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.service.AlertaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GET /api/alertas contra PostGIS de verdad: AlertaSpec y la política de
 * AlertaService.listar.
 *
 * La razón de ser de esta prueba es el filtro de zona: ST_DistanceSphere llega a
 * la base de datos como una función dentro de Criteria, y que Hibernate la
 * traduzca bien solo se sabe ejecutándola.
 *
 * Todas las consultas filtran por una especie aleatoria de esta prueba: la base
 * de datos es compartida y sin ese filtro saldrían alertas de otras.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class AlertaListadoQueryTest {

    /** Centro de la prueba. 0,01º de latitud son unos 1.111 m. */
    private static final double LAT = 40.20;
    private static final double LNG = -3.80;

    private final GeometryFactory geometrias = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    private AlertaService alertaService;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private Usuario autor;
    private String especie;
    private final List<Long> alertas = new ArrayList<>();

    @BeforeEach
    void preparar() {
        especie = "listado-" + UUID.randomUUID().toString().substring(0, 8);
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Listado");
        nuevo.setEmail("listado-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        autor = usuarioRepository.save(nuevo);
    }

    @AfterEach
    void limpiar() {
        alertaRepository.deleteAllById(alertas);
        usuarioRepository.delete(autor);
    }

    @Test
    @DisplayName("Por defecto solo las ACTIVA; ENCONTRADA si se pide, y nunca las otras dos")
    void estados() {
        long activa = alerta(EstadoAlerta.ACTIVA, LAT, 0);
        long encontrada = alerta(EstadoAlerta.ENCONTRADA, LAT, 0);
        alerta(EstadoAlerta.RETIRADA, LAT, 0);
        alerta(EstadoAlerta.ELIMINADA, LAT, 0);

        assertThat(ids(listar(null, null, null, null))).containsExactly(activa);
        assertThat(ids(listar(EstadoAlerta.ENCONTRADA, null, null, null))).containsExactly(encontrada);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("Pedir RETIRADA o ELIMINADA es 400: lo retirado no se navega")
    @EnumSource(value = EstadoAlerta.class, names = {"RETIRADA", "ELIMINADA"})
    void loRetiradoNoSeLista(EstadoAlerta estado) {
        assertThatThrownBy(() -> listar(estado, null, null, null))
                .isInstanceOf(ParametrosInvalidosException.class);
    }

    @Test
    @DisplayName("La especie se compara sin distinguir mayúsculas")
    void especieSinMayusculas() {
        long activa = alerta(EstadoAlerta.ACTIVA, LAT, 0);

        assertThat(ids(alertaService.listar("  " + especie.toUpperCase() + " ", null, null, null, null,
                PageRequest.of(0, 20)))).containsExactly(activa);
    }

    @Test
    @DisplayName("La zona es un círculo en metros reales: a 1 km entra, a 11 km no, con radio de 5 km")
    void zona() {
        long cerca = alerta(EstadoAlerta.ACTIVA, LAT + 0.01, 0);
        alerta(EstadoAlerta.ACTIVA, LAT + 0.10, 0);

        assertThat(ids(listar(null, LAT, LNG, 5_000.0))).containsExactly(cerca);
    }

    @Test
    @DisplayName("La zona exige los tres parámetros, y el radio tiene tope")
    void zonaIncompletaODesmedida() {
        assertThatThrownBy(() -> listar(null, LAT, null, 5_000.0)).isInstanceOf(ParametrosInvalidosException.class);
        assertThatThrownBy(() -> listar(null, LAT, LNG, 500_000.0)).isInstanceOf(ParametrosInvalidosException.class);
    }

    @Test
    @DisplayName("Las más recientes primero, y la página no pasa de 100")
    void ordenYTope() {
        long antigua = alerta(EstadoAlerta.ACTIVA, LAT, 2);
        long reciente = alerta(EstadoAlerta.ACTIVA, LAT, 0);

        PaginaResponse<AlertaResponse> pagina = alertaService.listar(especie, null, null, null, null,
                PageRequest.of(0, 500));

        assertThat(ids(pagina)).containsExactly(reciente, antigua);
        assertThat(pagina.getSize()).as("tope de página").isEqualTo(100);
        assertThat(pagina.getContent().get(0).getAutor().getNombre())
                .as("el autor viaja ya cargado, sin una consulta por tarjeta")
                .isEqualTo("Listado");
    }

    // ---------------------------------------------------------------------

    private PaginaResponse<AlertaResponse> listar(EstadoAlerta estado, Double lat, Double lng, Double radio) {
        return alertaService.listar(especie, estado, lat, lng, radio, PageRequest.of(0, 20));
    }

    private static List<Long> ids(PaginaResponse<AlertaResponse> pagina) {
        return pagina.getContent().stream().map(AlertaResponse::getId).toList();
    }

    private long alerta(EstadoAlerta estado, double latitud, int diasDeAntiguedad) {
        Alerta alerta = new Alerta();
        alerta.setUsuario(autor);
        alerta.setDescripcion("Para el listado");
        alerta.setEspecie(especie);
        alerta.setFechaAvistamiento(Instant.now());
        alerta.setUltimaUbicacion(geometrias.createPoint(new Coordinate(LNG, latitud)));
        alerta.setEstado(estado);
        Long id = alertaRepository.save(alerta).getId();
        alertas.add(id);
        jdbc.update("UPDATE alerta SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(86_400L * diasDeAntiguedad)), id);
        return id;
    }
}
