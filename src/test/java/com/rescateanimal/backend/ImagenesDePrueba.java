package com.rescateanimal.backend;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Imágenes fabricadas para las pruebas de la subida de fotos.
 *
 * Los metadatos se insertan a mano, byte a byte, porque ImageIO no escribe EXIF:
 * así la prueba controla exactamente qué llega al servidor y puede buscar el
 * marcador en lo que sale.
 */
public final class ImagenesDePrueba {

    private static final byte[] FIRMA_PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private ImagenesDePrueba() {
    }

    public static byte[] jpeg(int ancho, int alto) {
        return escribir(imagen(ancho, alto, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    public static byte[] png(int ancho, int alto) {
        return escribir(imagen(ancho, alto, BufferedImage.TYPE_INT_ARGB), "png");
    }

    /**
     * El JPEG con un segmento APP1 "Exif" justo detrás del SOI, que es donde lo
     * pone una cámara, y el marcador dentro como si fueran las coordenadas.
     */
    public static byte[] conExif(byte[] jpeg, String marcador) {
        byte[] carga = concatenar("Exif\0\0MM\0*".getBytes(StandardCharsets.ISO_8859_1),
                marcador.getBytes(StandardCharsets.ISO_8859_1));
        int longitud = carga.length + 2;
        byte[] segmento = concatenar(new byte[]{(byte) 0xFF, (byte) 0xE1,
                (byte) (longitud >> 8), (byte) longitud}, carga);
        return concatenar(slice(jpeg, 0, 2), segmento, slice(jpeg, 2, jpeg.length));
    }

    /** El PNG con un chunk tEXt detrás del IHDR que lleva el marcador. */
    public static byte[] conTextoPng(byte[] png, String marcador) {
        byte[] texto = ("Comment\0" + marcador).getBytes(StandardCharsets.ISO_8859_1);
        int finIhdr = FIRMA_PNG.length + 8 + 13 + 4;
        return concatenar(slice(png, 0, finIhdr), chunk("tEXt", texto), slice(png, finIhdr, png.length));
    }

    /**
     * Un PNG que solo tiene cabecera: declara las dimensiones pedidas y no trae
     * ni un píxel. Sirve para comprobar que se rechaza antes de decodificar.
     */
    public static byte[] cabeceraPng(int ancho, int alto) {
        byte[] ihdr = ByteBuffer.allocate(13).putInt(ancho).putInt(alto)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).array();
        return concatenar(FIRMA_PNG, chunk("IHDR", ihdr), chunk("IEND", new byte[0]));
    }

    public static boolean contiene(byte[] datos, String texto) {
        return new String(datos, StandardCharsets.ISO_8859_1).contains(texto);
    }

    public static BufferedImage leer(byte[] datos) {
        try {
            return ImageIO.read(new ByteArrayInputStream(datos));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage imagen(int ancho, int alto, int tipo) {
        BufferedImage imagen = new BufferedImage(ancho, alto, tipo);
        Graphics2D lienzo = imagen.createGraphics();
        lienzo.setColor(new Color(200, 120, 40));
        lienzo.fillRect(0, 0, ancho, alto);
        lienzo.dispose();
        return imagen;
    }

    private static byte[] escribir(BufferedImage imagen, String formato) {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            ImageIO.write(imagen, formato, salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] chunk(String tipo, byte[] datos) {
        byte[] tipoBytes = tipo.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(tipoBytes);
        crc.update(datos);
        return ByteBuffer.allocate(12 + datos.length)
                .putInt(datos.length).put(tipoBytes).put(datos).putInt((int) crc.getValue())
                .array();
    }

    private static byte[] slice(byte[] datos, int desde, int hasta) {
        byte[] trozo = new byte[hasta - desde];
        System.arraycopy(datos, desde, trozo, 0, trozo.length);
        return trozo;
    }

    private static byte[] concatenar(byte[]... partes) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            salida.writeBytes(parte);
        }
        return salida.toByteArray();
    }
}
