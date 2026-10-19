package com.rescateanimal.backend.service;

import com.rescateanimal.backend.ImagenesDePrueba;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La limpieza diaria de fotos contra PostgreSQL y el disco de verdad.
 *
 * La antigüedad se fabrica a mano: created_at con un UPDATE directo —la entidad
 * lo marca como no actualizable— y la fecha de modificación del archivo con
 * setLastModifiedTime. Así se prueba el criterio de las 24 horas sin esperar.
 *
 * LA BASE DE DATOS ES COMPARTIDA con el resto de pruebas y con el desarrollo,
 * así que se comprueba qué le pasa a cada foto de esta prueba, no los totales.
 * Lo único ajeno que puede borrar es lo que la limpieza de la propia aplicación
 * borraría igual: filas de más de un día a las que no apunta nadie.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class LimpiezaFotosTest {

    private static final Duration DOS_DIAS = Duration.ofDays(2);

    @Autowired
    private LimpiezaFotosService limpieza;
    @Autowired
    private AlmacenFotosService almacen;
    @Autowired
    private FotoRepository fotoRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Value("${app.fotos.directorio}")
    private String directorio;

    private Usuario usuario;
    private final List<Long> alertas = new ArrayList<>();
    private final List<String> nombres = new ArrayList<>();

    @BeforeEach
    void crearUsuario() {
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Limpieza");
        nuevo.setEmail("limpieza-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        usuario = usuarioRepository.save(nuevo);
    }

    @AfterEach
    void limpiar() {
        alertaRepository.deleteAllById(alertas);
        usuario.setFotoUrl(null);
        usuarioRepository.save(usuario);
        nombres.forEach(almacen::borrar);
        fotoRepository.deleteAllByIdInBatch(nombres);
        usuarioRepository.delete(usuario);
    }

    @Test
    @DisplayName("Borra las fotos de más de un día que no usa nadie, con su archivo, y respeta el resto")
    void fotosSinUso() throws Exception {
        String viejaSinUso = foto(DOS_DIAS);
        String viejaEnPerfil = foto(DOS_DIAS);
        String viejaEnAlerta = foto(DOS_DIAS);
        String recienteSinUso = foto(Duration.ZERO);

        // La del perfil, con OTRA base de URL: la de otro entorno que comparte la
        // base de datos. Se compara por nombre, así que cuenta como usada.
        usuario.setFotoUrl("http://localhost:8080/fotos/" + viejaEnPerfil);
        usuarioRepository.save(usuario);
        alertaCon(almacen.urlDe(viejaEnAlerta));

        LimpiezaFotosService.Resultado resultado = limpieza.limpiar();

        assertThat(resultado.fotosSinUso()).isPositive();
        assertBorrada(viejaSinUso);
        assertConservada(viejaEnPerfil);
        assertConservada(viejaEnAlerta);
        assertConservada(recienteSinUso);
    }

    @Test
    @DisplayName("Borra los archivos de más de un día sin fila y los temporales viejos, no los recientes")
    void archivosSinFila() throws Exception {
        String viejoSinFila = archivoSinFila(DOS_DIAS);
        String recienteSinFila = archivoSinFila(Duration.ZERO);
        Path temporalViejo = temporal(DOS_DIAS);
        Path temporalReciente = temporal(Duration.ZERO);

        LimpiezaFotosService.Resultado resultado = limpieza.limpiar();

        assertThat(resultado.archivosSinFila()).isPositive();
        assertThat(resultado.temporales()).isPositive();
        assertThat(almacen.abrir(viejoSinFila)).isEmpty();
        assertThat(almacen.abrir(recienteSinFila))
                .as("puede ser una subida cuyo INSERT aún no se ha confirmado")
                .isPresent();
        assertThat(temporalViejo).doesNotExist();
        assertThat(temporalReciente).exists();
        Files.deleteIfExists(temporalReciente);
    }

    // ---------------------------------------------------------------------

    /** Una foto con fila y archivo, envejecida lo pedido. Devuelve su nombre. */
    private String foto(Duration edad) throws Exception {
        String nombre = almacen.guardar(ImagenesDePrueba.jpeg(4, 4), "jpg");
        nombres.add(nombre);
        Foto foto = new Foto();
        foto.setNombre(nombre);
        foto.setUsuario(usuario);
        foto.setTamanyo(10);
        fotoRepository.save(foto);

        Instant creada = Instant.now().minus(edad);
        jdbc.update("UPDATE foto SET created_at = ? WHERE nombre = ?", Timestamp.from(creada), nombre);
        Files.setLastModifiedTime(almacen.abrir(nombre).orElseThrow(), FileTime.from(creada));
        return nombre;
    }

    private String archivoSinFila(Duration edad) throws Exception {
        String nombre = almacen.guardar(ImagenesDePrueba.jpeg(4, 4), "jpg");
        nombres.add(nombre);
        Files.setLastModifiedTime(almacen.abrir(nombre).orElseThrow(), FileTime.from(Instant.now().minus(edad)));
        return nombre;
    }

    private Path temporal(Duration edad) throws Exception {
        Path temporal = Files.createTempFile(Path.of(directorio).toAbsolutePath(), "subida-", ".tmp");
        Files.setLastModifiedTime(temporal, FileTime.from(Instant.now().minus(edad)));
        return temporal;
    }

    private void alertaCon(String fotoUrl) {
        Alerta alerta = new Alerta();
        alerta.setUsuario(usuario);
        alerta.setDescripcion("Para la limpieza");
        alerta.setEspecie("limpieza-" + UUID.randomUUID().toString().substring(0, 8));
        alerta.setFechaAvistamiento(Instant.now());
        alerta.setEstado(EstadoAlerta.ACTIVA);
        alerta.setFotoUrl(fotoUrl);
        alertas.add(alertaRepository.save(alerta).getId());
    }

    private void assertBorrada(String nombre) {
        assertThat(fotoRepository.existsById(nombre)).as("fila de " + nombre).isFalse();
        assertThat(almacen.abrir(nombre)).as("archivo de " + nombre + ", borrado tras el commit").isEmpty();
    }

    private void assertConservada(String nombre) {
        assertThat(fotoRepository.existsById(nombre)).as("fila de " + nombre).isTrue();
        assertThat(almacen.abrir(nombre)).as("archivo de " + nombre).isPresent();
    }
}
