package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

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

    private Usuario duenyo;
    private Usuario otro;
    private final List<String> fotosCreadas = new ArrayList<>();

    @BeforeEach
    void sembrar() {
        duenyo = crearUsuario();
        otro = crearUsuario();
    }

    @AfterEach
    void limpiar() {
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
