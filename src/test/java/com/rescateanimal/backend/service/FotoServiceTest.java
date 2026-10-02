package com.rescateanimal.backend.service;

import com.rescateanimal.backend.ImagenesDePrueba;
import com.rescateanimal.backend.dto.response.FotoResponse;
import com.rescateanimal.backend.exception.FotoNoValidaException;
import com.rescateanimal.backend.exception.FotoUrlNoValidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que FotoService hace con los bytes: qué acepta, qué guarda y qué borra.
 *
 * El almacén es el de verdad sobre una carpeta temporal, para poder abrir lo que
 * se ha escrito y comprobar que los metadatos no han llegado al disco.
 */
class FotoServiceTest {

    private static final String BASE = "https://fotos.test/fotos";
    private static final Long ID_USUARIO = 7L;

    @TempDir
    Path carpeta;

    private final FotoRepository fotoRepository = mock(FotoRepository.class);
    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private AlmacenFotosService almacen;
    private FotoService servicio;

    @BeforeEach
    void preparar() {
        almacen = new AlmacenFotosService(carpeta.toString(), BASE, true);
        servicio = new FotoService(fotoRepository, usuarioRepository, almacen);
        when(usuarioRepository.getReferenceById(ID_USUARIO)).thenReturn(new Usuario());
        when(fotoRepository.save(any(Foto.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void cerrarSincronizacion() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Nested
    @DisplayName("Los metadatos no llegan al disco")
    class Metadatos {

        @Test
        @DisplayName("El EXIF de un JPEG, con sus coordenadas, desaparece")
        void exifFuera() throws Exception {
            byte[] conGps = ImagenesDePrueba.conExif(ImagenesDePrueba.jpeg(40, 30), "GPS-40.4168,-3.7038");
            assertThat(ImagenesDePrueba.contiene(conGps, "GPS-40.4168")).as("la entrada sí lo lleva").isTrue();

            byte[] guardado = guardadoDe(servicio.subir(conGps, ID_USUARIO));

            assertThat(ImagenesDePrueba.contiene(guardado, "GPS-40.4168")).isFalse();
            assertThat(ImagenesDePrueba.contiene(guardado, "Exif")).isFalse();
            assertThat(ImagenesDePrueba.leer(guardado).getWidth()).isEqualTo(40);
        }

        @Test
        @DisplayName("El texto de un PNG desaparece y la transparencia se conserva")
        void textoPngFuera() throws Exception {
            byte[] conTexto = ImagenesDePrueba.conTextoPng(ImagenesDePrueba.png(20, 10), "CASA-DE-ANA");
            assertThat(ImagenesDePrueba.leer(conTexto)).as("el PNG de entrada es válido").isNotNull();

            FotoResponse subida = servicio.subir(conTexto, ID_USUARIO);
            byte[] guardado = guardadoDe(subida);

            assertThat(subida.getUrl()).endsWith(".png");
            assertThat(ImagenesDePrueba.contiene(guardado, "CASA-DE-ANA")).isFalse();
            assertThat(ImagenesDePrueba.leer(guardado).getColorModel().hasAlpha()).isTrue();
        }
    }

    @Nested
    @DisplayName("Solo se acepta un JPEG o un PNG de verdad")
    class Formato {

        @Test
        @DisplayName("Un SVG, un texto o nada: 400 sin tocar el disco ni la tabla")
        void noEsImagen() throws Exception {
            for (byte[] contenido : new byte[][]{
                    "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8),
                    "hola".getBytes(StandardCharsets.UTF_8),
                    new byte[0]}) {
                assertThatThrownBy(() -> servicio.subir(contenido, ID_USUARIO))
                        .isInstanceOf(FotoNoValidaException.class);
            }
            assertDiscoVacio();
            verify(fotoRepository, never()).save(any());
        }

        @Test
        @DisplayName("La firma de un JPEG seguida de basura: 400")
        void firmaSinImagen() throws Exception {
            byte[] falso = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};

            assertThatThrownBy(() -> servicio.subir(falso, ID_USUARIO))
                    .isInstanceOf(FotoNoValidaException.class);
            assertDiscoVacio();
        }

        @Test
        @DisplayName("Una cabecera de 100.000 x 100.000 se rechaza sin decodificar")
        void dimensionesAbsurdas() throws Exception {
            byte[] bomba = ImagenesDePrueba.cabeceraPng(100_000, 100_000);

            assertThatThrownBy(() -> servicio.subir(bomba, ID_USUARIO))
                    .isInstanceOf(FotoNoValidaException.class)
                    .hasMessageContaining("dimensiones");
            assertDiscoVacio();
        }
    }

    @Test
    @DisplayName("Una foto grande se guarda con el lado mayor en 2048 o menos")
    void reduce() throws Exception {
        BufferedImage guardada = ImagenesDePrueba.leer(guardadoDe(
                servicio.subir(ImagenesDePrueba.jpeg(5000, 300), ID_USUARIO)));

        assertThat(Math.max(guardada.getWidth(), guardada.getHeight()))
                .isLessThanOrEqualTo(FotoService.LADO_MAXIMO)
                .isGreaterThan(FotoService.LADO_MAXIMO / 2);
    }

    @ParameterizedTest(name = "{0} x {1} -> paso {2}")
    @DisplayName("El paso de submuestreo es el mínimo que deja el lado mayor en 2048")
    @CsvSource({"100,100,1", "2048,10,1", "2049,10,2", "10,4096,2", "4097,10,3", "8000,6000,4"})
    void paso(int ancho, int alto, int esperado) {
        assertThat(FotoService.pasoDeSubmuestreo(ancho, alto)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("Registra quién la subió y lo que ocupa de verdad")
    void registraLaFila() {
        servicio.subir(ImagenesDePrueba.jpeg(10, 10), ID_USUARIO);

        ArgumentCaptor<Foto> fila = ArgumentCaptor.forClass(Foto.class);
        verify(fotoRepository).save(fila.capture());
        assertThat(fila.getValue().getNombre()).endsWith(".jpg");
        assertThat(fila.getValue().getTamanyo()).isPositive();
        assertThat(fila.getValue().isNew()).as("un INSERT directo, sin SELECT previo").isTrue();
    }

    @Nested
    @DisplayName("Si la transacción no se confirma")
    class SinConfirmar {

        @Test
        @DisplayName("Un rollback borra el archivo ya escrito")
        void rollbackBorra() {
            TransactionSynchronizationManager.initSynchronization();
            FotoResponse subida = servicio.subir(ImagenesDePrueba.jpeg(10, 10), ID_USUARIO);
            String nombre = almacen.nombreDe(subida.getUrl()).orElseThrow();
            assertThat(almacen.abrir(nombre)).isPresent();

            completar(TransactionSynchronization.STATUS_ROLLED_BACK);

            assertThat(almacen.abrir(nombre)).isEmpty();
        }

        @Test
        @DisplayName("Un commit lo deja donde está")
        void commitConserva() {
            TransactionSynchronizationManager.initSynchronization();
            FotoResponse subida = servicio.subir(ImagenesDePrueba.jpeg(10, 10), ID_USUARIO);

            completar(TransactionSynchronization.STATUS_COMMITTED);

            assertThat(almacen.abrir(almacen.nombreDe(subida.getUrl()).orElseThrow())).isPresent();
        }

        private void completar(int estado) {
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCompletion(estado);
            }
        }
    }

    @Nested
    @DisplayName("Exigir que el fotoUrl sea una foto propia")
    class ExigirPropia {

        private static final String NOMBRE = "3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg";

        @Test
        @DisplayName("Una foto subida por el mismo usuario pasa")
        void propia() {
            when(fotoRepository.existsByNombreAndUsuarioId(NOMBRE, ID_USUARIO)).thenReturn(true);

            servicio.exigirPropia(BASE + "/" + NOMBRE, ID_USUARIO);
        }

        @Test
        @DisplayName("La de otro usuario, aunque sea de este almacén, no")
        void ajena() {
            when(fotoRepository.existsByNombreAndUsuarioId(NOMBRE, ID_USUARIO)).thenReturn(false);

            assertThatThrownBy(() -> servicio.exigirPropia(BASE + "/" + NOMBRE, ID_USUARIO))
                    .isInstanceOf(FotoUrlNoValidaException.class)
                    .hasMessageContaining("otro usuario");
        }

        @Test
        @DisplayName("Una URL https externa no llega ni a consultar la tabla")
        void externa() {
            for (String url : List.of(
                    "https://cdn.test/luna.jpg",
                    "https://fotos.test.evil.com/fotos/" + NOMBRE,
                    BASE + "/../" + NOMBRE,
                    "javascript:alert(1)")) {
                assertThatThrownBy(() -> servicio.exigirPropia(url, ID_USUARIO))
                        .as(url)
                        .isInstanceOf(FotoUrlNoValidaException.class)
                        .hasMessageContaining("/api/fotos");
            }
            verify(fotoRepository, never()).existsByNombreAndUsuarioId(any(), any());
        }
    }

    @Nested
    @DisplayName("Retirar la foto de un perfil")
    class Retirar {

        @Test
        @DisplayName("La fila se borra ya y el archivo solo tras el commit")
        void filaYaArchivoTrasCommit() {
            String nombre = almacen.guardar(new byte[]{1}, "jpg");
            String url = almacen.urlDe(nombre);
            when(fotoRepository.existsByNombreAndUsuarioId(nombre, ID_USUARIO)).thenReturn(true);
            TransactionSynchronizationManager.initSynchronization();

            servicio.retirar(url, ID_USUARIO);

            verify(fotoRepository).deleteById(nombre);
            assertThat(almacen.abrir(nombre)).as("antes del commit sigue en disco").isPresent();

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(almacen.abrir(nombre)).isEmpty();
        }

        @Test
        @DisplayName("Si la transacción se deshace, el archivo sigue ahí para su fila restaurada")
        void rollbackConservaElArchivo() {
            String nombre = almacen.guardar(new byte[]{1}, "jpg");
            when(fotoRepository.existsByNombreAndUsuarioId(nombre, ID_USUARIO)).thenReturn(true);
            TransactionSynchronizationManager.initSynchronization();

            servicio.retirar(almacen.urlDe(nombre), ID_USUARIO);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            assertThat(almacen.abrir(nombre)).isPresent();
        }

        @Test
        @DisplayName("Si una publicación la usa, se conserva: ya es la foto de un animal")
        void usadaEnPublicacion() {
            String nombre = almacen.guardar(new byte[]{1}, "jpg");
            String url = almacen.urlDe(nombre);
            when(fotoRepository.existsByNombreAndUsuarioId(nombre, ID_USUARIO)).thenReturn(true);
            when(fotoRepository.usadaEnPublicaciones(url)).thenReturn(true);

            servicio.retirar(url, ID_USUARIO);

            verify(fotoRepository, never()).deleteById(any());
            assertThat(almacen.abrir(nombre)).isPresent();
        }

        @Test
        @DisplayName("Una foto externa o ajena no se borra, y no es un error")
        void externaOAjena() {
            String nombre = almacen.guardar(new byte[]{1}, "jpg");
            when(fotoRepository.existsByNombreAndUsuarioId(nombre, ID_USUARIO)).thenReturn(false);

            servicio.retirar("https://example.test/ana.jpg", ID_USUARIO);
            servicio.retirar(almacen.urlDe(nombre), ID_USUARIO);

            verify(fotoRepository, never()).deleteById(any());
            assertThat(almacen.abrir(nombre)).isPresent();
        }
    }

    @Nested
    @DisplayName("Servir una foto")
    class Servir {

        @Test
        @DisplayName("Con fila y archivo, devuelve el archivo")
        void sirve() throws Exception {
            String nombre = almacen.guardar(new byte[]{9, 8, 7}, "jpg");
            when(fotoRepository.existsById(nombre)).thenReturn(true);

            assertThat(servicio.abrir(nombre).getContentAsByteArray()).containsExactly(9, 8, 7);
        }

        @Test
        @DisplayName("Un archivo sin fila no se sirve: la baja ya la borró aunque el disco no")
        void sinFila() {
            String nombre = almacen.guardar(new byte[]{1}, "jpg");
            when(fotoRepository.existsById(nombre)).thenReturn(false);

            assertThatThrownBy(() -> servicio.abrir(nombre)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Una fila sin archivo es 404, no 500")
        void sinArchivo() {
            String nombre = "3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg";
            when(fotoRepository.existsById(nombre)).thenReturn(true);

            assertThatThrownBy(() -> servicio.abrir(nombre)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Un nombre fuera del patrón es 404 sin consultar la base de datos")
        void nombreInvalido() {
            assertThatThrownBy(() -> servicio.abrir("../application.properties"))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(fotoRepository, never()).existsById(any());
        }
    }

    private byte[] guardadoDe(FotoResponse subida) throws Exception {
        String nombre = almacen.nombreDe(subida.getUrl()).orElseThrow();
        return Files.readAllBytes(almacen.abrir(nombre).orElseThrow());
    }

    private void assertDiscoVacio() throws Exception {
        try (var archivos = Files.list(carpeta)) {
            assertThat(archivos).isEmpty();
        }
    }
}
