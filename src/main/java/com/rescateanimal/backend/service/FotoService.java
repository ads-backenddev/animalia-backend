package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.FotoResponse;
import com.rescateanimal.backend.exception.FotoNoValidaException;
import com.rescateanimal.backend.exception.FotoUrlNoValidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;

/**
 * Subida de fotos (POST /api/fotos): de los bytes que manda el cliente a un
 * archivo limpio en disco y su fila en FOTO.
 *
 * EL FORMATO LO DICEN LOS BYTES, no el nombre del archivo ni el Content-Type,
 * que los pone el cliente y pueden decir cualquier cosa. Solo se aceptan JPEG y
 * PNG: son los que ImageIO lee y escribe sin dependencias, y los que produce
 * cualquier móvil.
 *
 * SIEMPRE SE RECODIFICA. Lo que se guarda no es lo que llegó, sino la imagen
 * decodificada y escrita de nuevo, y eso hace dos cosas a la vez:
 * - Quita todos los metadatos, EXIF incluido. Una foto hecha con el móvil lleva
 *   las coordenadas GPS de donde se hizo, y una foto de perfil hecha en casa
 *   publicaría el domicilio de su dueño.
 * - Neutraliza los archivos que son una imagen válida y otra cosa a la vez: lo
 *   que se sirve después son píxeles escritos por el servidor.
 * El coste es la orientación: también es un dato EXIF, así que el cliente tiene
 * que mandar la imagen ya girada (docs/api-rest.md, §1 Fotos).
 *
 * SE REDUCE AL DECODIFICAR, NO DESPUÉS. Si el lado mayor pasa de LADO_MAXIMO, el
 * lector decodifica saltándose píxeles (submuestreo) y nunca llega a tener la
 * imagen entera en memoria. Así un archivo de 5 MB que declara 50 megapíxeles
 * —una foto de móvil normal, o una "bomba" hecha a propósito— cuesta lo mismo
 * que una foto pequeña. Las dimensiones se leen de la cabecera antes de
 * decodificar nada, y MAX_PIXELES rechaza las que no tienen sentido.
 *
 * SI LA TRANSACCIÓN NO SE CONFIRMA, EL ARCHIVO SE BORRA. El archivo se escribe
 * antes del INSERT; si el INSERT o el commit fallan, una retrollamada de
 * afterCompletion lo quita. Si ni eso llega a ejecutarse —el proceso cae entre
 * medias—, el archivo queda sin fila y lo recoge la limpieza diaria.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FotoService {

    /** Lado mayor de lo que se guarda. Sobra para un móvil y para el panel. */
    static final int LADO_MAXIMO = 2048;

    /** Por encima de esto la cabecera miente o la imagen no es una foto. */
    static final long MAX_PIXELES = 100_000_000L;

    private static final float CALIDAD_JPEG = 0.85f;

    private final FotoRepository fotoRepository;
    private final UsuarioRepository usuarioRepository;
    private final AlmacenFotosService almacen;

    @Transactional
    public FotoResponse subir(byte[] contenido, Long usuarioId) {
        Formato formato = Formato.detectar(contenido)
                .orElseThrow(() -> new FotoNoValidaException("La foto debe ser una imagen JPEG o PNG"));

        byte[] limpia = recodificar(contenido, formato);
        String nombre = almacen.guardar(limpia, formato.extension);
        borrarSiNoSeConfirma(nombre);

        Foto foto = new Foto();
        foto.setNombre(nombre);
        foto.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        foto.setTamanyo(limpia.length);
        fotoRepository.save(foto);

        log.debug("Foto {} subida por el usuario {} ({} bytes)", nombre, usuarioId, limpia.length);
        return FotoResponse.builder().url(almacen.urlDe(nombre)).build();
    }

    /**
     * Exige que la URL sea la de una foto que este usuario subió a POST
     * /api/fotos. Es la comprobación de cada fotoUrl que llega a la API.
     *
     * Dos motivos, y por eso no basta con que la URL sea "de las nuestras":
     * - Una URL externa no se puede borrar —ni en la baja (RF-19) ni en la
     *   limpieza— y además deja a ese servidor ajeno ver la IP de cada persona
     *   que cargue la imagen.
     * - Una foto de otro usuario se puede copiar de cualquier respuesta pública.
     *   Usarla en tu perfil haría que su dueño, al darse de baja, borrase la foto
     *   de tu perfil, y al revés: que la tuya publicase la suya.
     *
     * Lo que haga cada service con un valor ausente o que no ha cambiado es
     * cosa suya: aquí solo llega lo que hay que comprobar.
     */
    @Transactional(readOnly = true)
    public void exigirPropia(String url, Long usuarioId) {
        String nombre = almacen.nombreDe(url).orElseThrow(() ->
                new FotoUrlNoValidaException("la foto debe ser una URL devuelta por POST /api/fotos"));
        if (!fotoRepository.existsByNombreAndUsuarioId(nombre, usuarioId)) {
            throw new FotoUrlNoValidaException("la foto no existe o la ha subido otro usuario");
        }
    }

    /**
     * Quita una foto que ha dejado de ser la de un perfil: porque se cambió, se
     * quitó, o porque la cuenta se anonimiza (RF-19). Solo la de perfil: las de
     * alertas, avistamientos y animales son fotos de animales y forman parte del
     * histórico que la baja conserva.
     *
     * DOS PASOS, CADA UNO EN SU MOMENTO:
     * - La fila se borra AHORA, dentro de la transacción de quien llama. Si esa
     *   transacción se deshace, la fila vuelve, y con ella la foto. Y como
     *   GET /fotos solo sirve lo que tiene fila, la foto deja de publicarse en el
     *   instante del commit.
     * - El archivo se borra DESPUÉS DEL COMMIT (afterCommit). Antes no se puede:
     *   un rollback dejaría la fila apuntando a un archivo que ya no existe. Si
     *   este segundo paso falla, el archivo queda sin fila —ya no se sirve— y lo
     *   recoge la limpieza diaria.
     *
     * NO SE BORRA, Y NO ES UN ERROR, cuando:
     * - la URL no es de este almacén: una foto anterior a /api/fotos, que el
     *   backend no aloja (limitación aceptada, docs/api-rest.md §20);
     * - la foto no es de este usuario, cosa que exigirPropia ya impide y aquí se
     *   comprueba de nuevo porque borrar es irreversible;
     * - alguna publicación la usa: el usuario puede poner su propia foto en una
     *   alerta, y entonces es la foto de un animal y se queda.
     */
    @Transactional
    public void retirar(String url, Long usuarioId) {
        Optional<String> encontrada = almacen.nombreDe(url);
        if (encontrada.isEmpty()) {
            log.debug("Foto de perfil externa del usuario {}: no se aloja aquí, no se borra", usuarioId);
            return;
        }
        String nombre = encontrada.get();
        if (!fotoRepository.existsByNombreAndUsuarioId(nombre, usuarioId)) {
            log.warn("El perfil del usuario {} apuntaba a la foto {}, que no es suya", usuarioId, nombre);
            return;
        }
        if (fotoRepository.usadaEnPublicaciones(nombre)) {
            log.debug("La foto {} sigue en una publicación del usuario {}: se conserva", nombre, usuarioId);
            return;
        }

        fotoRepository.deleteById(nombre);
        almacen.borrarTrasConfirmar(nombre);
    }

    /**
     * El archivo de GET /fotos/{nombre}, o 404.
     *
     * SOLO SE SIRVE LO QUE TIENE FILA, aunque el archivo esté en disco. Al
     * anonimizar una cuenta (RF-19) la fila de su foto de perfil se borra dentro
     * de la transacción y el archivo después del commit; si ese segundo paso
     * fallara, el archivo seguiría ahí hasta la limpieza diaria. Mirando la fila,
     * la foto deja de publicarse en el mismo instante en que se confirma la baja,
     * y lo mismo vale para el archivo de una subida cuyo INSERT no llegó a
     * confirmarse. Cuesta una búsqueda por clave primaria por imagen, y cada
     * imagen se pide una vez: la respuesta se cachea como inmutable.
     *
     * Un nombre fuera del patrón ni siquiera llega a la base de datos.
     */
    @Transactional(readOnly = true)
    public Resource abrir(String nombre) {
        if (!almacen.esNombreValido(nombre) || !fotoRepository.existsById(nombre)) {
            throw new ResourceNotFoundException("La foto no existe");
        }
        return almacen.abrir(nombre)
                .map(FileSystemResource::new)
                .orElseThrow(() -> {
                    log.warn("La foto {} tiene fila pero no archivo", nombre);
                    return new ResourceNotFoundException("La foto no existe");
                });
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private void borrarSiNoSeConfirma(String nombre) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int estado) {
                if (estado != STATUS_COMMITTED) {
                    almacen.borrar(nombre);
                }
            }
        });
    }

    private static byte[] recodificar(byte[] contenido, Formato formato) {
        try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(contenido))) {
            Iterator<ImageReader> lectores = ImageIO.getImageReadersByFormatName(formato.nombreImageIo);
            if (entrada == null || !lectores.hasNext()) {
                throw new FotoNoValidaException("No se ha podido leer la imagen");
            }
            ImageReader lector = lectores.next();
            try {
                // ignoreMetadata: lo que no se lee no hay que descartarlo después.
                lector.setInput(entrada, true, true);
                int ancho = lector.getWidth(0);
                int alto = lector.getHeight(0);
                if (ancho <= 0 || alto <= 0 || (long) ancho * alto > MAX_PIXELES) {
                    throw new FotoNoValidaException("Las dimensiones de la imagen no son válidas");
                }

                ImageReadParam parametros = lector.getDefaultReadParam();
                int paso = pasoDeSubmuestreo(ancho, alto);
                parametros.setSourceSubsampling(paso, paso, 0, 0);

                return escribir(lector.read(0, parametros), formato);
            } finally {
                lector.dispose();
            }
        } catch (FotoNoValidaException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Un JPEG en CMYK, un PNG truncado, una cabecera corrupta: ImageIO
            // lanza de todo, y para el cliente todo significa lo mismo.
            throw new FotoNoValidaException("No se ha podido leer la imagen");
        }
    }

    /** El menor paso entero que deja el lado mayor en LADO_MAXIMO o menos. */
    static int pasoDeSubmuestreo(int ancho, int alto) {
        int ladoMayor = Math.max(ancho, alto);
        return Math.max(1, (ladoMayor + LADO_MAXIMO - 1) / LADO_MAXIMO);
    }

    private static byte[] escribir(BufferedImage imagen, Formato formato) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (formato == Formato.PNG) {
            ImageIO.write(imagen, "png", bytes);
            return bytes.toByteArray();
        }

        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam parametros = escritor.getDefaultWriteParam();
        parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parametros.setCompressionQuality(CALIDAD_JPEG);
        try (ImageOutputStream salida = ImageIO.createImageOutputStream(bytes)) {
            escritor.setOutput(salida);
            // Metadatos null: el escritor pone solo la cabecera JFIF mínima.
            escritor.write(null, new IIOImage(aRgb(imagen), null, null), parametros);
        } finally {
            escritor.dispose();
        }
        return bytes.toByteArray();
    }

    /**
     * El escritor JPEG de ImageIO falla o produce colores falsos con canal alfa
     * o paleta; en RGB plano siempre funciona.
     */
    private static BufferedImage aRgb(BufferedImage imagen) {
        if (imagen.getType() == BufferedImage.TYPE_INT_RGB) {
            return imagen;
        }
        BufferedImage rgb = new BufferedImage(imagen.getWidth(), imagen.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D lienzo = rgb.createGraphics();
        try {
            lienzo.drawImage(imagen, 0, 0, null);
        } finally {
            lienzo.dispose();
        }
        return rgb;
    }

    enum Formato {
        JPEG("jpg", "jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
        PNG("png", "png", new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

        final String extension;
        final String nombreImageIo;
        private final byte[] firma;

        Formato(String extension, String nombreImageIo, byte[] firma) {
            this.extension = extension;
            this.nombreImageIo = nombreImageIo;
            this.firma = firma;
        }

        static Optional<Formato> detectar(byte[] contenido) {
            if (contenido == null) {
                return Optional.empty();
            }
            return Arrays.stream(values())
                    .filter(f -> contenido.length > f.firma.length
                            && Arrays.equals(contenido, 0, f.firma.length, f.firma, 0, f.firma.length))
                    .findFirst();
        }
    }
}
