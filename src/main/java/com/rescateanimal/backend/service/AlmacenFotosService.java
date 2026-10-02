package com.rescateanimal.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

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
