package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.ActualizarAnimalRequest;
import com.rescateanimal.backend.dto.request.CrearAnimalRequest;
import com.rescateanimal.backend.dto.response.AnimalAdopcionDetalleResponse;
import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.model.enums.Tamanyo;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AnimalAdopcionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Catálogo de adopción: gestión de animales por la protectora, filtros y
 * confirmación de adopción (RF-07, RF-20, RF-22).
 *
 * DOS NIVELES DE PERMISO. El rol lo impone @PreAuthorize, que basta para
 * "publicar": cualquier protectora activa puede hacerlo. No basta para editar,
 * retirar o confirmar la adopción, que exigen además ser la protectora dueña de
 * ESE animal —algo que depende del recurso y no del rol, así que no cabe en una
 * anotación—. Esa segunda mitad la resuelve el service y responde 403.
 *
 * Las consultas quedan abiertas a cualquier autenticado: el catálogo es el
 * escaparate de adopción (RF-22) y lo consulta el ciudadano, no la protectora.
 */
@RestController
@RequestMapping("/api/adopcion")
@RequiredArgsConstructor
public class AnimalAdopcionController {

    private final AnimalAdopcionService animalAdopcionService;

    /**
     * POST /api/adopcion -> 201, 400, 401, 403
     *
     * La protectora sale del token, así que el cuerpo no la lleva. Con Location,
     * a diferencia de un mensaje de chat: un animal sí tiene URL propia.
     */
    @PostMapping
    @PreAuthorize("hasRole('PROTECTORA')")
    public ResponseEntity<AnimalAdopcionResponse> publicar(
            @Valid @RequestBody CrearAnimalRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario,
            UriComponentsBuilder uriBuilder) {

        AnimalAdopcionResponse creado = animalAdopcionService.publicar(peticion, usuario.getId());
        URI ubicacion = uriBuilder.path("/api/adopcion/{id}")
                .buildAndExpand(creado.getId()).toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    /**
     * PUT /api/adopcion/{id} -> 200, 400, 401, 403, 404
     *
     * El 403 con código ANIMAL_AJENO es intentar editar el animal de otra
     * protectora; el 403 sin cuerpo propio, no tener el rol.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PROTECTORA')")
    public ResponseEntity<AnimalAdopcionResponse> actualizar(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarAnimalRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(animalAdopcionService.actualizar(id, peticion, usuario.getId()));
    }

    /**
     * DELETE /api/adopcion/{id} -> 204, 401, 403, 404
     * Retira el animal del catálogo.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PROTECTORA')")
    public ResponseEntity<Void> retirar(
            @PathVariable Long id,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        animalAdopcionService.retirar(id, usuario.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * PUT /api/adopcion/{id}/adoptado -> 200, 401, 403, 404
     *
     * Confirma la adopción completada (RF-20). Es PUT y no PATCH por el mismo
     * motivo que el marcado de leídos del chat: no toca un subconjunto de campos
     * sino que fija el estado del animal, y repetirlo lo deja igual.
     */
    @PutMapping("/{id}/adoptado")
    @PreAuthorize("hasRole('PROTECTORA')")
    public ResponseEntity<AnimalAdopcionResponse> confirmarAdopcion(
            @PathVariable Long id,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(animalAdopcionService.confirmarAdopcion(id, usuario.getId()));
    }

    /**
     * GET /api/adopcion -> 200, 400, 401
     *
     * Catálogo paginado de animales DISPONIBLE, con los filtros de RF-22
     * combinables entre sí. Los tres son opcionales; omitirlos devuelve el
     * catálogo entero paginado.
     *
     * El 400 lo produce un tamanyo que no es del enum, que Spring rechaza al
     * convertir el parámetro.
     */
    @GetMapping
    public ResponseEntity<PaginaResponse<AnimalAdopcionResponse>> catalogo(
            @RequestParam(required = false) String especie,
            @RequestParam(required = false) Tamanyo tamanyo,
            @RequestParam(required = false) String zona,
            @PageableDefault(size = 20) Pageable paginacion) {

        return ResponseEntity.ok(
                animalAdopcionService.catalogo(especie, tamanyo, zona, paginacion));
    }

    /**
     * GET /api/adopcion/feed -> 200, 400, 401
     *
     * Tarjetas para el swipe de adopción (RF-09, RF-15): el catálogo menos los
     * animales propios y los que el usuario ya deslizó.
     *
     * Va declarado ANTES que /{id} a propósito. Spring resuelve primero la ruta
     * más específica, así que hoy funcionaría igual en cualquier orden, pero
     * dejarlo aquí evita que un futuro cambio de mapeo convierta "feed" en un id
     * que no se puede convertir a Long.
     *
     * Admite los mismos filtros que el catálogo, porque quien busca un perro
     * pequeño en su zona quiere deslizar sobre eso y no sobre todo lo publicado.
     * No pagina: se devuelve una tanda acotada, igual que el feed de
     * avistamientos.
     */
    @GetMapping("/feed")
    public ResponseEntity<List<AnimalAdopcionResponse>> feed(
            @RequestParam(required = false) String especie,
            @RequestParam(required = false) Tamanyo tamanyo,
            @RequestParam(required = false) String zona,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(
                animalAdopcionService.feed(especie, tamanyo, zona, usuario.getId()));
    }

    /**
     * GET /api/adopcion/{id} -> 200, 401, 404
     * Detalle del animal con los datos públicos de su protectora.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AnimalAdopcionDetalleResponse> detalle(@PathVariable Long id) {
        return ResponseEntity.ok(animalAdopcionService.detalle(id));
    }
}
