package com.rescateanimal.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlmacenFotosServiceTest {

    private static final String BASE = "https://fotos.test/fotos";

    @TempDir
    Path carpeta;

    private AlmacenFotosService almacen;

    @BeforeEach
    void preparar() {
        almacen = new AlmacenFotosService(carpeta.toString(), BASE, true);
    }

    @Nested
    @DisplayName("Arranque")
    class Arranque {

        @Test
        @DisplayName("Exigiendo https, una base http no deja arrancar")
        void baseHttpConExigenciaFalla() {
            assertThatThrownBy(() -> new AlmacenFotosService(carpeta.toString(), "http://x/fotos", true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("https");
        }

        @Test
        @DisplayName("Sin exigir https, la base http de desarrollo vale")
        void baseHttpSinExigenciaArranca() {
            AlmacenFotosService local = new AlmacenFotosService(carpeta.toString(), "http://localhost:8080/fotos/", false);
            assertThat(local.urlDe("a")).isEqualTo("http://localhost:8080/fotos/a");
        }

        @Test
        @DisplayName("Crea la carpeta si no existe")
        void creaLaCarpeta() {
            Path nueva = carpeta.resolve("no/existe/aun");
            new AlmacenFotosService(nueva.toString(), BASE, true);
            assertThat(nueva).isDirectory();
        }
    }

    @Test
    @DisplayName("Guarda con un nombre UUID y la URL vuelve a dar ese nombre")
    void guardarYRecuperar() throws Exception {
        String nombre = almacen.guardar(new byte[]{1, 2, 3}, "jpg");

        assertThat(almacen.esNombreValido(nombre)).isTrue();
        assertThat(almacen.abrir(nombre)).hasValueSatisfying(ruta ->
                assertThat(ruta).hasBinaryContent(new byte[]{1, 2, 3}));
        assertThat(almacen.nombreDe(almacen.urlDe(nombre))).contains(nombre);
        try (var archivos = Files.list(carpeta)) {
            assertThat(archivos).as("sin temporales sueltos").hasSize(1);
        }
    }

    @Test
    @DisplayName("Una extensión que no es jpg ni png no se guarda")
    void extensionDesconocida() {
        assertThatThrownBy(() -> almacen.guardar(new byte[]{1}, "svg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @DisplayName("Ningún nombre fuera del patrón llega al disco")
    @ValueSource(strings = {
            "../secreto.jpg",
            "..%2Fsecreto.jpg",
            "/etc/passwd",
            "3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.svg",
            "3F1C2A9E-0B7D-4C55-9A8E-6D2F1B0C7E4A.jpg",
            "3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg/../../x",
            "subida-123.tmp"
    })
    void nombresInvalidos(String nombre) throws Exception {
        Files.writeString(carpeta.resolve("subida-123.tmp"), "x");

        assertThat(almacen.abrir(nombre)).isEmpty();
        assertThat(almacen.borrar(nombre)).isFalse();
        assertThat(carpeta.resolve("subida-123.tmp")).exists();
    }

    @Test
    @DisplayName("Borrar quita el archivo, y borrar uno que ya no está cuenta como hecho")
    void borrar() {
        String nombre = almacen.guardar(new byte[]{1}, "png");

        assertThat(almacen.borrar(nombre)).isTrue();
        assertThat(almacen.abrir(nombre)).isEmpty();
        assertThat(almacen.borrar(nombre)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("Una URL ajena al almacén no da nombre")
    @ValueSource(strings = {
            "https://otro.test/fotos/3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg",
            "https://fotos.test/fotosx/3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg",
            "https://fotos.test/fotos/sub/3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg",
            "https://fotos.test/fotos/",
            "https://fotos.test/fotos"
    })
    void urlAjena(String url) {
        assertThat(almacen.nombreDe(url)).isEmpty();
    }
}
