package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearReporteRequest;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.ReporteResponse;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Reporte;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoReporte;
import com.rescateanimal.backend.model.enums.TipoObjetivo;
import com.rescateanimal.backend.repository.ReporteRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporte de publicaciones y usuarios que incumplen las normas (RF-26).
 *
 * QUIÉN PUEDE REPORTAR: cualquier usuario autenticado, sin más requisitos. Es
 * deliberado y es lo contrario del criterio de las valoraciones (RF-23), donde sí
 * se exige haber tratado con la protectora. La diferencia está en qué pasa si uno
 * se equivoca: una valoración falsa altera una reputación pública en el acto,
 * mientras que un reporte no publica nada ni retira nada — solo pone el caso
 * delante de un administrador, que decide. Poner barreras a avisar de un animal
 * en peligro sería el error caro.
 *
 * NADA SE RETIRA AUTOMÁTICAMENTE por acumular reportes, y conviene decirlo porque
 * es la tentación evidente: un umbral automático convierte el botón de reportar en
 * un arma, porque basta con organizar unas cuantas cuentas para tumbar la
 * publicación de un competidor. La retirada la decide una persona con
 * DELETE /api/admin/publicaciones/{id} (RF-11).
 *
 * EL OBJETIVO SE COMPRUEBA, no se cree. Un reporte contra algo que no existe no
 * es inofensivo: llena la cola del administrador de trabajo imposible de juzgar y
 * permitiría sondear qué ids existen si además se respondiera distinto. Se
 * responde 404 igual para lo inexistente que para lo mal formado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReporteService {

    private final ReporteRepository reporteRepository;
    private final UsuarioRepository usuarioRepository;
    private final PublicacionService publicacionService;

    /**
     * Registra un reporte en estado PENDIENTE (RF-26).
     *
     * El estado inicial no viaja en el cuerpo ni podría: quien reporta no decide
     * si su reporte está revisado. Lo fija aquí el service, y solo el
     * administrador puede moverlo a REVISADO (RF-11).
     */
    @Transactional
    public ReporteResponse reportar(CrearReporteRequest peticion, Long usuarioId) {
        Usuario autor = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El usuario " + usuarioId + " no existe"));

        exigirObjetivoValido(peticion, usuarioId);

        Reporte reporte = new Reporte();
        reporte.setUsuario(autor);
        reporte.setTipoObjetivo(peticion.getTipoObjetivo());
        reporte.setObjetivoId(peticion.getObjetivoId());
        reporte.setTipoContenido(peticion.getTipoContenido());
        reporte.setMotivo(peticion.getMotivo());
        reporte.setEstado(EstadoReporte.PENDIENTE);

        Reporte guardado = reporteRepository.save(reporte);

        // No se registra a quién se reporta: el log lo lee cualquiera con acceso
        // al servidor, y una acusación sin revisar no es un dato que deba quedar
        // ahí (RNF-10). El caso completo está en la tabla, para el administrador.
        log.info("Reporte {} registrado por el usuario {} sobre un objetivo de tipo {}",
                guardado.getId(), usuarioId, peticion.getTipoObjetivo());

        return aResponse(guardado);
    }

    /**
     * Comprueba que lo reportado existe y que los campos son coherentes entre sí.
     *
     * El switch sobre TipoObjetivo no lleva rama por defecto: si algún día se
     * admite reportar algo más —un mensaje de chat, por ejemplo— esto deja de
     * compilar y señala el sitio, en vez de aceptar en silencio un reporte que
     * nadie sabría contra qué contrastar.
     */
    private void exigirObjetivoValido(CrearReporteRequest peticion, Long usuarioId) {
        switch (peticion.getTipoObjetivo()) {
            case PUBLICACION -> {
                if (peticion.getTipoContenido() == null) {
                    throw new ParametrosInvalidosException(
                            "Al reportar una publicación hay que indicar tipoContenido");
                }
                publicacionService.exigirQueExista(
                        peticion.getTipoContenido(), peticion.getObjetivoId());
            }
            case USUARIO -> {
                if (peticion.getTipoContenido() != null) {
                    throw new ParametrosInvalidosException(
                            "tipoContenido solo aplica al reportar una publicación");
                }
                if (peticion.getObjetivoId().equals(usuarioId)) {
                    // Se comprueba antes de mirar si existe: sabemos que existe,
                    // es quien está haciendo la petición, y un 404 ahí sería
                    // desconcertante. Es un 400 y no un 403 porque no falta
                    // ningún permiso: reportarse a uno mismo no es una operación
                    // que este dominio admita, igual que deslizar la publicación
                    // propia.
                    throw new OperacionNoPermitidaException("AUTORREPORTE",
                            "No puedes reportarte a ti mismo");
                }
                if (!usuarioRepository.existsById(peticion.getObjetivoId())) {
                    throw new ResourceNotFoundException(
                            "El usuario " + peticion.getObjetivoId() + " no existe");
                }
            }
        }
    }

    // ---------------------------------------------------------------------

    /**
     * QUÉ SE DEVUELVE DE UN REPORTE, y sobre todo qué no.
     *
     * Del autor, solo AutorResponse: id, nombre y foto, sin email ni teléfono
     * (RNF-10). Del objetivo, solo el par (tipo, id) —más tipoContenido cuando es
     * una publicación—, nunca los datos de la persona reportada. Es importante que
     * siga siendo así: un reporte es una acusación sin revisar, y devolver el
     * contacto del acusado a quien lo denuncia, o a cualquiera que liste reportes,
     * convertiría el endpoint en una forma cómoda de sacar el teléfono de alguien
     * con quien se ha discutido.
     */
    static ReporteResponse aResponse(Reporte reporte) {
        Usuario autor = reporte.getUsuario();
        return ReporteResponse.builder()
                .id(reporte.getId())
                .autor(AutorResponse.builder()
                        .id(autor.getId())
                        .nombre(autor.getNombre())
                        .fotoUrl(autor.getFotoUrl())
                        .build())
                .tipoObjetivo(reporte.getTipoObjetivo())
                .objetivoId(reporte.getObjetivoId())
                .tipoContenido(reporte.getTipoContenido())
                .motivo(reporte.getMotivo())
                .estado(reporte.getEstado())
                .createdAt(reporte.getCreatedAt())
                .build();
    }
}
