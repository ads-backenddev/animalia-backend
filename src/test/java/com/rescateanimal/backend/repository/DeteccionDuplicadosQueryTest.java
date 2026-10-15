package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La consulta de RF-25 (AlertaRepository.buscarSimilar) contra PostGIS de verdad.
 *
 * Hasta ahora la detección solo se probaba con el repositorio simulado, que es
 * justo lo que no puede decir si la ventana mira la fecha correcta. Aquí cada
 * prueba fabrica una alerta existente con su created_at y su fecha_avistamiento
 * —el primero con un UPDATE directo, porque la entidad lo marca como no
 * actualizable— y pregunta si bloquearía una nueva en el mismo sitio.
 *
 * Especie aleatoria por prueba, para no chocar con alertas de otras pruebas que
 * comparten la base de datos.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class DeteccionDuplicadosQueryTest {

    private static final double LAT = 40.30;
    private static final double LNG = -3.90;
    private static final double RADIO = 500;
    private static final Duration VENTANA = Duration.ofHours(24);
    private static final long NINGUNA = 0L;

    private final GeometryFactory geometrias = new GeometryFactory(new PrecisionModel(), 4326);

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
        especie = "rf25-" + UUID.randomUUID().toString().substring(0, 8);
        Usuario nuevo = new Usuario();
        nuevo.setNombre("RF-25");
        nuevo.setEmail("rf25-" + UUID.randomUUID() + "@test.es");
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
    @DisplayName("Publicada hoy sobre un animal visto hace dos días: bloquea (antes no lo hacía)")
    void publicadaHoyAvistadaHaceDias() {
        existente(Duration.ZERO, Duration.ofDays(2), EstadoAlerta.ACTIVA);

        assertThat(buscar(NINGUNA)).isPresent();
    }

    @Test
    @DisplayName("Publicada hace días sobre un animal visto hace una hora: bloquea, como siempre")
    void publicadaHaceDiasAvistadaHoy() {
        existente(Duration.ofDays(2), Duration.ofHours(1), EstadoAlerta.ACTIVA);

        assertThat(buscar(NINGUNA)).isPresent();
    }

    @Test
    @DisplayName("Publicada y avistada hace más de un día: ya no bloquea")
    void antiguaPorLasDosFechas() {
        existente(Duration.ofDays(2), Duration.ofDays(2), EstadoAlerta.ACTIVA);

        assertThat(buscar(NINGUNA)).isEmpty();
    }

    @Test
    @DisplayName("Al editar, la propia alerta no se cuenta como duplicada de sí misma")
    void seExcluyeLaPropia() {
        long propia = existente(Duration.ZERO, Duration.ZERO, EstadoAlerta.ACTIVA);

        assertThat(buscar(propia)).isEmpty();
        assertThat(buscar(NINGUNA)).isPresent();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("Una alerta que ya no está ACTIVA no bloquea, sea cual sea el motivo")
    @EnumSource(value = EstadoAlerta.class, names = {"ENCONTRADA", "RETIRADA", "ELIMINADA"})
    void soloLasActivas(EstadoAlerta estado) {
        existente(Duration.ZERO, Duration.ZERO, estado);

        assertThat(buscar(NINGUNA)).isEmpty();
    }

    @Test
    @DisplayName("El radio son 500 m reales: a unos 400 m bloquea, a unos 600 m no")
    void radioDeQuinientosMetros() {
        // 0,0036º de latitud son unos 400 m, y 0,0054º unos 600 m, a cualquier longitud.
        existente(Duration.ZERO, Duration.ZERO, EstadoAlerta.ACTIVA, LAT + 0.0054);
        assertThat(buscar(NINGUNA)).as("a 600 m").isEmpty();

        long cerca = existente(Duration.ZERO, Duration.ZERO, EstadoAlerta.ACTIVA, LAT + 0.0036);
        assertThat(buscar(NINGUNA)).as("a 400 m").get().extracting(Alerta::getId).isEqualTo(cerca);
    }

    // ---------------------------------------------------------------------

    private java.util.Optional<Alerta> buscar(long excluirId) {
        return alertaRepository.buscarSimilar(especie, LAT, LNG, RADIO, Instant.now().minus(VENTANA), excluirId);
    }

    /** Una alerta en el punto de la prueba, publicada y avistada hace lo indicado. */
    private long existente(Duration publicadaHace, Duration avistadaHace, EstadoAlerta estado) {
        return existente(publicadaHace, avistadaHace, estado, LAT);
    }

    private long existente(Duration publicadaHace, Duration avistadaHace, EstadoAlerta estado, double latitud) {
        Alerta alerta = new Alerta();
        alerta.setUsuario(autor);
        alerta.setDescripcion("Para RF-25");
        alerta.setEspecie(especie);
        alerta.setFechaAvistamiento(Instant.now().minus(avistadaHace));
        alerta.setUltimaUbicacion(geometrias.createPoint(new Coordinate(LNG, latitud)));
        alerta.setEstado(estado);
        Long id = alertaRepository.save(alerta).getId();
        alertas.add(id);
        jdbc.update("UPDATE alerta SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(publicadaHace)), id);
        return id;
    }
}
