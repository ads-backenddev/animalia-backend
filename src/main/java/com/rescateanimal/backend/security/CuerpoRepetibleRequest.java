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
 * El cuerpo se lee entero en el constructor y cada llamada obtiene un flujo
 * nuevo sobre la misma copia. Quien construya esto es responsable de no hacerlo
 * con peticiones grandes: aquí solo se envuelven cuerpos de autenticación, que
 * son unos cientos de bytes, y el filtro comprueba el tamaño antes.
 */
public class CuerpoRepetibleRequest extends HttpServletRequestWrapper {

    private final byte[] cuerpo;

    public CuerpoRepetibleRequest(HttpServletRequest original) throws IOException {
        super(original);
        this.cuerpo = original.getInputStream().readAllBytes();
    }

    /** El cuerpo tal cual llegó, para inspeccionarlo sin consumir nada. */
    public byte[] getCuerpo() {
        return cuerpo;
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
