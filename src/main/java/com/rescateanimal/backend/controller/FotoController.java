package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.FotoResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.FotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;

/**
 * Subida de fotos. Es el único endpoint de la API que recibe archivos: el resto
 * solo recibe la URL que este devuelve, en su campo fotoUrl.
 *
 * Requiere JWT, como todo lo que no es /api/auth. Este controller no mira el
 * archivo: el tamaño lo corta Spring (spring.servlet.multipart, 413) y el
 * contenido lo comprueba FotoService.
 */
@RestController
@RequestMapping("/api/fotos")
@RequiredArgsConstructor
public class FotoController {

    private final FotoService fotoService;

    /**
     * POST /api/fotos (multipart, parte "archivo") -> 201, 400, 401, 413
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FotoResponse> subir(
            @RequestParam("archivo") MultipartFile archivo,
            @AuthenticationPrincipal UsuarioPrincipal usuario) throws IOException {

        FotoResponse subida = fotoService.subir(archivo.getBytes(), usuario.getId());
        return ResponseEntity.created(URI.create(subida.getUrl())).body(subida);
    }
}
