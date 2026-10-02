package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.service.FotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Las fotos subidas, servidas a cualquiera que tenga la URL.
 *
 * ES PÚBLICO A PROPÓSITO (SecurityConfig): una etiqueta <img> no puede mandar la
 * cabecera Authorization, así que exigir token dejaría las fotos sin poder
 * pintarse en el panel. Lo que impide recorrerlas es el nombre, un UUID
 * aleatorio que solo conoce quien recibió la URL en alguna respuesta.
 *
 * Fuera de /api porque no es parte de la API JSON: es contenido estático, y la
 * URL pública que se guarda en fotoUrl apunta aquí (app.fotos.url-publica).
 *
 * CACHÉ INMUTABLE DE UN AÑO: un archivo no cambia nunca —una foto nueva lleva un
 * nombre nuevo—, así que el cliente no tiene por qué volver a pedirlo. El
 * X-Content-Type-Options: nosniff lo pone Spring Security en todas las
 * respuestas, y aquí importa: el navegador no debe tratar el archivo como otra
 * cosa que la imagen que dice el Content-Type.
 */
@RestController
@RequestMapping("/fotos")
@RequiredArgsConstructor
public class FotoPublicaController {

    private static final CacheControl INMUTABLE =
            CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final FotoService fotoService;

    /**
     * GET /fotos/{nombre} -> 200, 404
     */
    @GetMapping("/{nombre}")
    public ResponseEntity<Resource> servir(@PathVariable String nombre) {
        Resource archivo = fotoService.abrir(nombre);
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(archivo).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .cacheControl(INMUTABLE)
                .body(archivo);
    }
}
