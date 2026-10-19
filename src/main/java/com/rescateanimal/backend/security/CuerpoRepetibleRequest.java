package com.rescateanimal.backend.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Petición cuyo cuerpo puede leerse más de una vez.
 *
 * POR QUÉ HACE FALTA. El cuerpo de una petición HTTP es un flujo, y un flujo se
 * consume: quien lo lee primero se lo queda. LimiteIntentosAutenticacionFilter
 * necesita mirar el email para contar los intentos por cuenta, pero después el
 * controller tiene que poder deserializar ese mismo cuerpo. Sin este envoltorio,
 * el segundo lector encuentra el flujo vacío y la petición muere con un 400 que
 * no tiene nada que ver con lo que el cliente envió.
 *
 * NO SE USA ContentCachingRequestWrapper de Spring, que parece lo indicado y no
 * lo es: aquel guarda copia de lo que se va leyendo, no de lo que queda por
 * leer. Vaciar el flujo en el filtro deja igualmente sin cuerpo a quien va
 * detrás; solo sirve para inspeccionar el cuerpo DESPUÉS de que otro lo haya
 * consumido, que es el caso contrario a este.
 *
 * El cuerpo se lee en el constructor y cada llamada obtiene un flujo nuevo sobre
 * la misma copia.
 *
 * LA LECTURA VA ACOTADA, y el límite es parte del contrato de esta clase, no una
 * comprobación que pueda quedarse fuera. Antes leía con readAllBytes() y confiaba
 * en que quien la construyera hubiera mirado el tamaño; ese "mirar antes"
 * consistía en consultar Content-Length, que vale -1 cuando el cliente manda el
 * cuerpo con Transfer-Encoding: chunked, y -1 pasa cualquier comparación de
 * «cabe». El resultado era que un anónimo podía hacer que un endpoint público se
 * tragara en memoria un cuerpo del tamaño que quisiera. Con el tope aquí dentro,
 * el problema no puede volver por descuido de quien la use.
 */
public class CuerpoRepetibleRequest extends HttpServletRequestWrapper {

    private final byte[] cuerpo;
    private final boolean desbordado;

    /**
     * @param maximoBytes lo máximo que se acepta cargar en memoria. Si el cuerpo
     *                    lo supera, la petición queda marcada como desbordada y
     *                    NO se conserva: quien la construya debe rechazarla, no
     *                    dejarla seguir con un cuerpo a medias.
     */
    public CuerpoRepetibleRequest(HttpServletRequest original, int maximoBytes) throws IOException {
        super(original);

        // Un byte de más que el tope: es lo que distingue "justo en el límite" de
        // "se ha pasado" sin llegar a cargar lo que sobra.
        byte[] leido = original.getInputStream().readNBytes(maximoBytes + 1);

        this.desbordado = leido.length > maximoBytes;
        this.cuerpo = desbordado ? new byte[0] : leido;
    }

    /** El cuerpo tal cual llegó, para inspeccionarlo sin consumir nada. */
    public byte[] getCuerpo() {
        return cuerpo;
    }

    /** Cierto si el cuerpo excedía el tope: entonces no hay cuerpo utilizable. */
    public boolean isDesbordado() {
        return desbordado;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream origen = new ByteArrayInputStream(cuerpo);

        return new ServletInputStream() {
            @Override
            public int read() {
                return origen.read();
            }

            @Override
            public boolean isFinished() {
                return origen.available() == 0;
            }

            @Override
            public boolean isReady() {
                // Siempre listo: los datos ya están en memoria, no hay espera de red.
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // La lectura asíncrona no aplica sobre un array ya cargado.
                throw new UnsupportedOperationException("Este cuerpo se lee de forma síncrona");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
