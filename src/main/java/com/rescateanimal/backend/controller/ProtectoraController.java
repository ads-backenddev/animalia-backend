package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ProtectoraDetalleResponse;
import com.rescateanimal.backend.dto.response.ProtectoraDirectorioResponse;
import com.rescateanimal.backend.service.ProtectoraService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Directorio público de protectoras: listado y ficha (RF-06).
 *
 * El panel de gestión de RF-10 NO está aquí: vive en ProtectoraPanelController,
 * bajo /api/protectoras/panel. Son dos cosas distintas y separarlas es
 * deliberado: esto es lo que un ciudadano ve DE una protectora, y aquello es lo
 * que una protectora ve DE SÍ MISMA. El reparto de permisos no se parece —aquí
 * todo es para cualquier autenticado, allí todo exige rol PROTECTORA y estado
 * ACTIVA—, y tenerlos juntos habría mezclado los dos criterios en un mismo
 * controller. Emitir una valoración (RF-23) está separado por lo mismo, en
 * ValoracionController.
 *
 * SIN @PreAuthorize, y no es un olvido: el directorio no exige ningún rol.
 * SecurityConfig ya obliga a llevar token en todo lo que no sea /api/auth, así
 * que "público" aquí significa "cualquier usuario autenticado", no anónimo.
 * Dejarlo abierto sin token habría convertido el listado en una fuente de
 * direcciones de correo que cualquiera puede recorrer sin registrarse.
 *
 * TRES CONTROLLERS SOBRE EL MISMO PREFIJO, Y NINGUNA RUTA CHOCA. No hace falta
 * confiar en que Spring resuelva antes un segmento literal que un patrón: es que
 * no hay ninguna pareja ambigua. Las cuatro del panel tienen dos segmentos
 * (/panel/animales, /panel/resumen...) y la de valorar también
 * (/{id}/valoracion, además con PUT); las dos de aquí son la raíz y un único
 * segmento. Si algún día se añadiera aquí un GET /api/protectoras/{algo} de un
 * solo segmento habría que mirar esto otra vez, porque entonces "panel" sí
 * competiría con el patrón.
 */
@RestController
@RequestMapping("/api/protectoras")
@RequiredArgsConstructor
public class ProtectoraController {

    private final ProtectoraService protectoraService;

    /**
     * GET /api/protectoras -> 200, 400, 401
     *
     * Listado paginado de protectoras publicables, con su valoración media y su
     * información de contacto (RF-06). El filtro {@code zona} es opcional y se
     * busca por contenido: "Madrid" encuentra "Madrid, Carabanchel".
     *
     * SOLO SALEN LAS ACTIVA, y además con la cuenta en pie: una protectora
     * pendiente de verificar, rechazada, dada de baja o bloqueada no aparece.
     * Quién entra y quién no lo decide ProtectoraService, no este método ni el
     * cliente: no hay ningún parámetro con el que pedir otra cosa.
     */
    @GetMapping
    public ResponseEntity<PaginaResponse<ProtectoraDirectorioResponse>> directorio(
            @RequestParam(required = false) String zona,
            @PageableDefault(size = 20) Pageable paginacion) {

        return ResponseEntity.ok(protectoraService.directorio(zona, paginacion));
    }

    /**
     * GET /api/protectoras/{id} -> 200, 400, 401, 404
     *
     * Ficha de una protectora con sus animales disponibles y las valoraciones
     * más recientes que ha recibido (RF-06, RF-23).
     *
     * LOS PARÁMETROS page Y size PAGINAN LOS ANIMALES, que es lo único de esta
     * respuesta que puede crecer sin límite; las valoraciones llevan su propio
     * tope y el resto de la ficha tiene tamaño fijo. Es lo que evita el
     * GET /api/protectoras/{id}/animales que §9 tenía diseñado: una llamada menos
     * para pintar la misma pantalla.
     *
     * 404 TAMBIÉN SI EXISTE PERO NO ES VISIBLE —pendiente, rechazada, de baja o
     * bloqueada—, y no 403. Es una excepción consciente al criterio del resto de
     * la API: aquí lo que no debe poder averiguarse recorriendo ids es
     * precisamente que la fila está ahí. El porqué, en ProtectoraService.detalle.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProtectoraDetalleResponse> detalle(
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable paginacionAnimales) {

        return ResponseEntity.ok(protectoraService.detalle(id, paginacionAnimales));
    }
}
