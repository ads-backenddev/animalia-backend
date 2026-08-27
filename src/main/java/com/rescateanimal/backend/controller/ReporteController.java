package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.CrearReporteRequest;
import com.rescateanimal.backend.dto.response.ReporteResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.ReporteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporte de publicaciones o usuarios que incumplen las normas (RF-26).
 *
 * SIN @PreAuthorize, y es correcto: reportar está abierto a cualquier usuario
 * autenticado. SecurityConfig ya exige token para todo lo que no sea /api/auth,
 * y aquí no hay ningún rol que añadir — la barrera de entrada a avisar de un
 * animal en peligro debe ser la mínima posible.
 *
 * NO HAY ENDPOINT PARA CONSULTAR REPORTES en este controller, ni el propio ni el
 * ajeno, y la ausencia es deliberada. Leer reportes es trabajo de moderación y
 * vive en /api/admin/reportes, bajo rol ADMIN. Un listado "mis reportes" tendría
 * sentido de cara al usuario, pero abriría la puerta a que la respuesta creciera
 * con datos del contenido reportado —y, con él, de quien lo publicó—, que es
 * justo lo que el diseño de RF-26 evita: un reporte es una acusación sin
 * revisar, y quien la emite no gana por ello ningún acceso a la otra parte.
 */
@RestController
@RequestMapping("/api/reportes")
@RequiredArgsConstructor
public class ReporteController {

    private final ReporteService reporteService;

    /**
     * POST /api/reportes -> 201, 400, 401, 404
     *
     * Registra un reporte en estado PENDIENTE para que un administrador lo
     * revise. No retira nada por sí solo: la decisión es siempre de una persona
     * (RF-11).
     *
     * 404 cuando lo reportado no existe —una publicación con ese tipo e id, o un
     * usuario—, con el mismo mensaje en los dos casos para no convertir el
     * endpoint en una forma de averiguar qué ids existen. 400 si `tipoContenido`
     * no encaja con `tipoObjetivo`, o al reportarse a uno mismo.
     *
     * SIN CABECERA Location, a diferencia de los demás 201 de esta API, y la
     * excepción tiene motivo: Location anuncia dónde leer lo que se acaba de
     * crear, y un reporte no tiene ninguna URL donde su autor pueda leerlo. Solo
     * se consulta desde /api/admin/reportes, que exige rol ADMIN. Apuntar ahí
     * daría una dirección que devuelve 403 a quien acaba de recibirla, que es
     * peor que no dar ninguna. El cuerpo de la respuesta ya lleva el reporte
     * completo, incluido su id.
     */
    @PostMapping
    public ResponseEntity<ReporteResponse> reportar(
            @Valid @RequestBody CrearReporteRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        ReporteResponse creado = reporteService.reportar(peticion, usuario.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }
}
