package com.rescateanimal.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Los archivos de las fotos en disco: guardarlos, encontrarlos y borrarlos, y
 * traducir entre el nombre del archivo y la URL pública con la que se sirve.
 *
 * No decide nada sobre quién puede subir qué ni cuándo se borra una foto: eso es
 * de FotoService, que sabe a qué usuario pertenece cada una. Este servicio solo
 * conoce nombres.
 *
 * EL NOMBRE ES LO ÚNICO QUE DEJA ACERCARSE AL DISCO, y por eso tiene un patrón
 * cerrado —un UUID en minúsculas y una extensión conocida—. Cualquier otra cosa,
 * "../../etc/passwd" incluido, no llega a resolverse contra la carpeta: se trata
 * como si la foto no existiera.
 *
 * Producción exige que la base pública sea https (app.fotos.exigir-https): con
 * http las fotos irían sin cifrar y el panel, servido por https, las bloquearía
 * como contenido mixto. El arranque falla antes que descubrirlo con la primera
 * foto rota.
 */
@Slf4j
@Service
public class AlmacenFotosService {

    private static final Pattern NOMBRE_VALIDO = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)$");

    /** Los temporales que crea guardar(): "subida-" + dígitos + ".tmp". */
    private static final Pattern TEMPORAL = Pattern.compile("^subida-\\d+\\.tmp$");

    private final Path directorio;
    private final String urlPublica;

    public AlmacenFotosService(@Value("${app.fotos.directorio}") String directorio,
                               @Value("${app.fotos.url-publica}") String urlPublica,
                               @Value("${app.fotos.exigir-https}") boolean exigirHttps) {
        if (exigirHttps && !urlPublica.startsWith("https://")) {
            throw new IllegalStateException(
                    "app.fotos.url-publica debe ser https en este entorno: " + urlPublica);
        }
        this.urlPublica = urlPublica.endsWith("/")
                ? urlPublica.substring(0, urlPublica.length() - 1)
                : urlPublica;
        this.directorio = prepararDirectorio(Path.of(directorio).toAbsolutePath().normalize());
    }

    /**
     * Guarda la imagen con un nombre nuevo y devuelve ese nombre.
     *
     * Se escribe primero en un temporal de la misma carpeta y luego se renombra:
     * así GET /fotos nunca sirve un archivo a medio escribir, y un fallo a mitad
     * deja como mucho un ".tmp" que ningún nombre válido puede pedir.
     *
     * @param extension "jpg" o "png", la del formato real ya comprobado
     */
    public String guardar(byte[] contenido, String extension) {
        String nombre = UUID.randomUUID() + "." + extension;
        if (!esNombreValido(nombre)) {
            throw new IllegalArgumentException("Extensión de foto no soportada: " + extension);
        }

        Path temporal = null;
        try {
            temporal = Files.createTempFile(directorio, "subida-", ".tmp");
            Files.write(temporal, contenido);
            Files.move(temporal, directorio.resolve(nombre), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            borrarSinFallar(temporal);
            throw new UncheckedIOException("No se pudo guardar la foto " + nombre, e);
        }
        return nombre;
    }

    /** La ruta del archivo, si el nombre es válido y el archivo existe. */
    public Optional<Path> abrir(String nombre) {
        if (!esNombreValido(nombre)) {
            return Optional.empty();
        }
        Path archivo = directorio.resolve(nombre);
        return Files.isRegularFile(archivo) ? Optional.of(archivo) : Optional.empty();
    }

    /**
     * Borra el archivo. Devuelve false si no se pudo, sin lanzar nada.
     *
     * Quien borra una foto lo hace como consecuencia de otra cosa —una baja, un
     * cambio de foto de perfil— que ya está confirmada y no debe fallar por esto.
     * Lo que quede en disco lo recoge la limpieza diaria. Que el archivo no
     * existiera ya cuenta como borrado: el resultado es el mismo.
     */
    public boolean borrar(String nombre) {
        if (!esNombreValido(nombre)) {
            return false;
        }
        try {
            Files.deleteIfExists(directorio.resolve(nombre));
            return true;
        } catch (IOException e) {
            log.warn("No se pudo borrar la foto {}", nombre, e);
            return false;
        }
    }

    /**
     * Borra el archivo cuando la transacción en curso se confirme, o ya si no
     * hay ninguna.
     *
     * Es el segundo paso de quitar una foto: la fila se borra dentro de la
     * transacción y el archivo solo después, porque un rollback devolvería una
     * fila que apuntaría a un archivo ya borrado. Si este paso falla, el
     * archivo queda sin fila —no se sirve— y lo recoge la limpieza diaria.
     */
    public void borrarTrasConfirmar(String nombre) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            borrar(nombre);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                borrar(nombre);
            }
        });
    }

    /**
     * Los nombres de foto cuyo archivo se modificó por última vez antes de
     * limite. Lo que no tenga nombre válido —los temporales, cualquier otra
     * cosa que alguien haya dejado en la carpeta— no aparece.
     */
    public List<String> nombresAnterioresA(Instant limite) {
        try (Stream<Path> archivos = Files.list(directorio)) {
            return archivos
                    .filter(archivo -> esNombreValido(archivo.getFileName().toString()))
                    .filter(archivo -> modificadoAntesDe(archivo, limite))
                    .map(archivo -> archivo.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo listar la carpeta de fotos " + directorio, e);
        }
    }

    /**
     * Borra los temporales de subidas que se interrumpieron entre escribir y
     * renombrar (guardar). Solo los de antes de limite: uno reciente puede ser
     * una subida en curso. Devuelve cuántos ha borrado.
     */
    public int borrarTemporalesAnterioresA(Instant limite) {
        try (Stream<Path> archivos = Files.list(directorio)) {
            List<Path> temporales = archivos
                    .filter(archivo -> TEMPORAL.matcher(archivo.getFileName().toString()).matches())
                    .filter(archivo -> modificadoAntesDe(archivo, limite))
                    .toList();
            temporales.forEach(AlmacenFotosService::borrarSinFallar);
            return temporales.size();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo listar la carpeta de fotos " + directorio, e);
        }
    }

    public String urlDe(String nombre) {
        return urlPublica + "/" + nombre;
    }

    /**
     * El nombre del archivo al que apunta una URL de las nuestras, o vacío si la
     * URL no es de este almacén o no lleva un nombre válido.
     */
    public Optional<String> nombreDe(String url) {
        if (url == null || !url.startsWith(urlPublica + "/")) {
            return Optional.empty();
        }
        String nombre = url.substring(urlPublica.length() + 1);
        return esNombreValido(nombre) ? Optional.of(nombre) : Optional.empty();
    }

    public boolean esNombreValido(String nombre) {
        return nombre != null && NOMBRE_VALIDO.matcher(nombre).matches();
    }

    private static Path prepararDirectorio(Path directorio) {
        try {
            Files.createDirectories(directorio);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo crear la carpeta de fotos " + directorio, e);
        }
        // En Railway el volumen se monta como root y la aplicación corre como
        // "spring": sin esta comprobación, el fallo aparecería en la primera subida.
        if (!Files.isWritable(directorio)) {
            throw new IllegalStateException("La carpeta de fotos no admite escritura: " + directorio);
        }
        log.info("Fotos en {}", directorio);
        return directorio;
    }

    /** Un archivo que no se puede leer cuenta como reciente: ante la duda, no se borra. */
    private static boolean modificadoAntesDe(Path archivo, Instant limite) {
        try {
            return Files.getLastModifiedTime(archivo).toInstant().isBefore(limite);
        } catch (IOException e) {
            log.warn("No se pudo leer la fecha de {}", archivo, e);
            return false;
        }
    }

    private static void borrarSinFallar(Path archivo) {
        if (archivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(archivo);
        } catch (IOException e) {
            log.warn("No se pudo borrar el temporal {}", archivo, e);
        }
    }
}
