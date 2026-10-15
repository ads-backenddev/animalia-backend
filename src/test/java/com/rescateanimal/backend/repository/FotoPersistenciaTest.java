package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La tabla FOTO de V14 contra PostgreSQL de verdad.
 *
 * Que el contexto arranque ya demuestra que la entidad casa con la migración
 * (ddl-auto=validate). Lo que validate NO comprueba —el CHECK del tamaño y la
 * clave ajena— se prueba aquí, porque solo existe si la migración lo crea.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class FotoPersistenciaTest {

    @Autowired
    private FotoRepository fotoRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private AvistamientoRepository avistamientoRepository;
    @Autowired
    private ProtectoraRepository protectoraRepository;
    @Autowired
    private AnimalAdopcionRepository animalAdopcionRepository;

    private Usuario duenyo;
    private Usuario otro;
    private final List<String> fotosCreadas = new ArrayList<>();
    private final List<Long> alertasCreadas = new ArrayList<>();
    private final List<Long> avistamientosCreados = new ArrayList<>();
    private final List<Long> animalesCreados = new ArrayList<>();
    private Protectora protectora;

    @BeforeEach
    void sembrar() {
        duenyo = crearUsuario();
        otro = crearUsuario();
    }

    @AfterEach
    void limpiar() {
        avistamientoRepository.deleteAllById(avistamientosCreados);
        animalAdopcionRepository.deleteAllById(animalesCreados);
        if (protectora != null) {
            protectoraRepository.delete(protectora);
        }
        alertaRepository.deleteAllById(alertasCreadas);
        fotoRepository.deleteAllById(fotosCreadas);
        usuarioRepository.delete(duenyo);
        usuarioRepository.delete(otro);
    }

    @Test
    @DisplayName("Se guarda con su fecha y la propiedad se comprueba por usuario")
    void propiedad() {
        Foto foto = guardar(duenyo, 1234);

        assertThat(fotoRepository.findById(foto.getNombre()))
                .hasValueSatisfying(leida -> {
                    assertThat(leida.getTamanyo()).isEqualTo(1234);
                    assertThat(leida.getCreatedAt()).isNotNull();
                });
        assertThat(fotoRepository.existsByNombreAndUsuarioId(foto.getNombre(), duenyo.getId())).isTrue();
        assertThat(fotoRepository.existsByNombreAndUsuarioId(foto.getNombre(), otro.getId())).isFalse();
        assertThat(fotoRepository.existsByNombreAndUsuarioId("no-existe.jpg", duenyo.getId())).isFalse();
    }

    @Test
    @DisplayName("El CHECK de V14 rechaza un tamaño cero")
    void tamanyoCero() {
        assertThatThrownBy(() -> guardar(duenyo, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("La clave ajena de V14 rechaza un usuario que no existe")
    void usuarioInexistente() {
        Usuario fantasma = new Usuario();
        fantasma.setId(Long.MAX_VALUE);

        assertThatThrownBy(() -> guardar(fantasma, 10))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("usadaFueraDeLaAlerta no cuenta a la propia alerta; sí a otra alerta y a un perfil")
    void usadaFueraDeLaAlerta() {
        String nombre = UUID.randomUUID() + ".jpg";
        String url = "https://otra-base.test/fotos/" + nombre;
        long propia = alertaCon(url);

        assertThat(fotoRepository.usadaFueraDeLaAlerta(nombre, propia)).as("solo la usa ella").isFalse();

        long otra = alertaCon(url);
        assertThat(fotoRepository.usadaFueraDeLaAlerta(nombre, propia)).as("otra alerta").isTrue();

        alertaRepository.deleteById(otra);
        alertasCreadas.remove(Long.valueOf(otra));
        otro.setFotoUrl(url);
        otro = usuarioRepository.save(otro);
        assertThat(fotoRepository.usadaFueraDeLaAlerta(nombre, propia)).as("un perfil").isTrue();
    }

    @Test
    @DisplayName("usadaFueraDeLaAlerta cuenta un avistamiento con la misma foto")
    void usadaPorUnAvistamiento() {
        String nombre = UUID.randomUUID() + ".jpg";
        String url = "https://otra-base.test/fotos/" + nombre;
        long propia = alertaCon(url);
        assertThat(fotoRepository.usadaFueraDeLaAlerta(nombre, propia)).as("solo la usa ella").isFalse();

        Avistamiento avistamiento = new Avistamiento();
        avistamiento.setUsuario(otro);
        avistamiento.setAlerta(alertaRepository.getReferenceById(propia));
        avistamiento.setFotoUrl(url);
        avistamiento.setDescripcion("Con la misma foto");
        avistamiento.setEstado(EstadoAvistamiento.VISIBLE);
        avistamientosCreados.add(avistamientoRepository.save(avistamiento).getId());

        assertThat(fotoRepository.usadaFueraDeLaAlerta(nombre, propia)).isTrue();
    }

    @Test
    @DisplayName("usadaFueraDeLaAlerta cuenta un animal en adopción con la misma foto")
    void usadaPorUnAnimal() {
        String nombre = UUID.randomUUID() + ".jpg";
        String url = "https://otra-base.test/fotos/" + nombre;
        long propia = alertaCon(url);
        assertThat(fotoRepository.usadaFueraDeLaAlerta(nombre, propia)).as("solo la usa ella").isFalse();

        Protectora nueva = new Protectora();
        nueva.setUsuario(otro);
        nueva.setNombreOrganizacion("Protectora " + UUID.randomUUID());
        nueva.setEstado(EstadoProtectora.ACTIVA);
        protectora = protectoraRepository.save(nueva);
        AnimalAdopcion animal = new AnimalAdopcion();
        animal.setProtectora(protectora);
        animal.setNombre("Toby");
        animal.setEspecie("perro");
        animal.setFotoUrl(url);
        animal.setEstado(EstadoAnimal.DISPONIBLE);
        animalesCreados.add(animalAdopcionRepository.save(animal).getId());

        assertThat(fotoRepository.usadaFueraDeLaAlerta(nombre, propia)).isTrue();
    }

    private long alertaCon(String fotoUrl) {
        Alerta alerta = new Alerta();
        alerta.setUsuario(duenyo);
        alerta.setDescripcion("Para la foto");
        alerta.setEspecie("foto-" + UUID.randomUUID().toString().substring(0, 8));
        alerta.setFechaAvistamiento(Instant.now());
        alerta.setFotoUrl(fotoUrl);
        alerta.setEstado(EstadoAlerta.ACTIVA);
        long id = alertaRepository.save(alerta).getId();
        alertasCreadas.add(id);
        return id;
    }

    private Foto guardar(Usuario usuario, int tamanyo) {
        Foto foto = new Foto();
        foto.setNombre(UUID.randomUUID() + ".jpg");
        foto.setUsuario(usuario);
        foto.setTamanyo(tamanyo);
        fotosCreadas.add(foto.getNombre());
        return fotoRepository.saveAndFlush(foto);
    }

    private Usuario crearUsuario() {
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Foto " + UUID.randomUUID());
        nuevo.setEmail("foto-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        return usuarioRepository.save(nuevo);
    }
}
