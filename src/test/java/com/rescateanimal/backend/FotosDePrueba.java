package com.rescateanimal.backend;

import com.jayway.jsonpath.JsonPath;
import com.rescateanimal.backend.model.Foto;
import com.rescateanimal.backend.repository.FotoRepository;
import com.rescateanimal.backend.repository.LimiteFotoDiarioRepository;
import com.rescateanimal.backend.service.AlmacenFotosService;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fotos reales para las pruebas de extremo a extremo.
 *
 * Desde que fotoUrl solo admite fotos subidas por el propio usuario a POST
 * /api/fotos, una prueba que publique un avistamiento, un animal o una foto de
 * perfil tiene que subirla antes, como haría el cliente. Y como FOTO apunta a
 * USUARIO, hay que borrar esas fotos antes que a sus dueños.
 */
public final class FotosDePrueba {

    private FotosDePrueba() {
    }

    /** Sube una foto con el token dado y devuelve la URL que hay que mandar en fotoUrl. */
    public static String subir(HttpClient http, int puerto, String token) throws Exception {
        String frontera = "----frontera" + UUID.randomUUID();
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.writeBytes(("--" + frontera + "\r\n"
                + "Content-Disposition: form-data; name=\"archivo\"; filename=\"foto.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        cuerpo.writeBytes(ImagenesDePrueba.jpeg(16, 16));
        cuerpo.writeBytes(("\r\n--" + frontera + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> respuesta = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + "/api/fotos"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "multipart/form-data; boundary=" + frontera)
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(respuesta.statusCode()).as("subida de la foto de prueba").isEqualTo(201);
        return JsonPath.read(respuesta.body(), "$.url");
    }

    /**
     * Borra las fotos —archivo y fila— de estos usuarios y sus contadores de
     * subidas del día. Va antes de borrar a los usuarios: FOTO y
     * LIMITE_FOTO_DIARIO tienen clave ajena a USUARIO.
     *
     * getUsuario().getId() es seguro sobre la entidad ya desconectada: pedirle
     * el id a un proxy no lo inicializa.
     */
    public static void borrarLasDe(Collection<Long> usuarios, FotoRepository fotoRepository,
                                   LimiteFotoDiarioRepository limiteRepository, AlmacenFotosService almacen) {
        List<Foto> suyas = fotoRepository.findAll().stream()
                .filter(f -> usuarios.contains(f.getUsuario().getId()))
                .toList();
        suyas.forEach(f -> almacen.borrar(f.getNombre()));
        fotoRepository.deleteAll(suyas);
        limiteRepository.deleteAll(limiteRepository.findAll().stream()
                .filter(l -> usuarios.contains(l.getUsuario().getId()))
                .toList());
    }
}
