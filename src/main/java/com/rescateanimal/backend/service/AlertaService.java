package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearAlertaRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.AlertaDuplicadaResponse;
import com.rescateanimal.backend.dto.response.AlertaMapaResponse;
import com.rescateanimal.backend.dto.response.AlertaResponse;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.EventoMapaResponse;
import com.rescateanimal.backend.dto.response.UbicacionResponse;
import com.rescateanimal.backend.exception.AlertaDuplicadaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Lógica de las alertas de mascota perdida: publicación con detección de
 * duplicados, mapa en tiempo real y cierre (RF-03, RF-04, RF-14, RF-25).
 */
@Slf4j
@Service
public class AlertaService {

    /** Canal STOMP al que se suscriben los clientes para refrescar el mapa (RF-04). */
    public static final String CANAL_MAPA = "/topic/mapa";

    /** SRID 4326: coordenadas geográficas WGS84, las mismas que da el GPS. */
    private static final int SRID_WGS84 = 4326;

    private final AlertaRepository alertaRepository;
    private final UsuarioRepository usuarioRepository;
    private final NotificacionService notificacionService;
    private final SimpMessagingTemplate mensajeria;

    private final double radioDuplicadosMetros;
    private final int ventanaDuplicadosHoras;

    /** Un GeometryFactory es inmutable y seguro entre hilos: basta con uno. */
    private final GeometryFactory geometryFactory =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    public AlertaService(AlertaRepository alertaRepository,
                         UsuarioRepository usuarioRepository,
                         NotificacionService notificacionService,
                         SimpMessagingTemplate mensajeria,
                         @Value("${app.alertas.radio-duplicados-metros}") double radioDuplicadosMetros,
                         @Value("${app.alertas.ventana-duplicados-horas}") int ventanaDuplicadosHoras) {
        this.alertaRepository = alertaRepository;
        this.usuarioRepository = usuarioRepository;
        this.notificacionService = notificacionService;
        this.mensajeria = mensajeria;
        this.radioDuplicadosMetros = radioDuplicadosMetros;
        this.ventanaDuplicadosHoras = ventanaDuplicadosHoras;
    }

    // ---------------------------------------------------------------------
    // Publicación (RF-03, RF-25)
    // ---------------------------------------------------------------------

    /**
     * Publica una alerta, salvo que ya exista una equivalente en la zona.
     *
     * El orden importa: primero se comprueba el duplicado y solo después se
     * guarda. RF-25 exige bloquear la publicación, no crearla y avisar.
     */
    @Transactional
    public AlertaResponse publicar(CrearAlertaRequest peticion, Long autorId) {
        comprobarQueNoEsDuplicada(peticion);

        Usuario autor = usuarioRepository.findById(autorId)
                .orElseThrow(() -> new ResourceNotFoundException("El usuario no existe"));

        Alerta alerta = new Alerta();
        alerta.setUsuario(autor);
        alerta.setDescripcion(peticion.getDescripcion());
        alerta.setFotoUrl(peticion.getFotoUrl());
        alerta.setEspecie(peticion.getEspecie());
        alerta.setFechaAvistamiento(peticion.getFechaAvistamiento());
        alerta.setUltimaUbicacion(aPunto(peticion.getUbicacion()));
        alerta.setRadioBusqueda(peticion.getRadioBusqueda());
        alerta.setEstado(EstadoAlerta.ACTIVA);

        Alerta guardada = alertaRepository.save(alerta);

        // Efectos derivados de la publicación: aviso a la zona (RF-05) y refresco
        // del mapa de quienes lo tengan abierto (RF-04).
        notificacionService.notificarAlertaCercana(guardada);
        publicarEnMapa(EventoMapaResponse.Accion.ALTA, guardada);

        return aAlertaResponse(guardada);
    }

    /**
     * Detección de alertas duplicadas (RF-25): misma especie, radio de 500 m y
     * ventana de 24 h, los tres a la vez.
     */
    private void comprobarQueNoEsDuplicada(CrearAlertaRequest peticion) {
        LocalDateTime desde = LocalDateTime.now().minusHours(ventanaDuplicadosHoras);
        double latitud = peticion.getUbicacion().getLatitud();
        double longitud = peticion.getUbicacion().getLongitud();

        Optional<Alerta> similar = alertaRepository.buscarSimilar(
                peticion.getEspecie(), latitud, longitud, radioDuplicadosMetros, desde);

        if (similar.isEmpty()) {
            return;
        }

        Alerta existente = similar.get();
        Double distancia = alertaRepository.distanciaMetros(existente.getId(), latitud, longitud);

        log.info("Alerta duplicada rechazada: ya existe la alerta {} a {} m",
                existente.getId(), distancia == null ? "?" : Math.round(distancia));

        throw new AlertaDuplicadaException(
                "Ya existe una alerta similar en la zona en las últimas "
                        + ventanaDuplicadosHoras + " horas",
                AlertaDuplicadaResponse.builder()
                        .id(existente.getId())
                        .especie(existente.getEspecie())
                        .distanciaMetros(distancia == null ? null : Math.round(distancia))
                        .build());
    }

    // ---------------------------------------------------------------------
    // Consultas (RF-04)
    // ---------------------------------------------------------------------

    /** Alertas activas y geolocalizadas, para pintar el mapa interactivo. */
    @Transactional(readOnly = true)
    public List<AlertaMapaResponse> listarParaMapa() {
        return alertaRepository.findByEstadoAndUltimaUbicacionIsNotNull(EstadoAlerta.ACTIVA)
                .stream()
                .map(this::aAlertaMapaResponse)
                .toList();
    }

    /** Alertas del usuario autenticado, en cualquier estado. */
    @Transactional(readOnly = true)
    public List<AlertaResponse> listarMisAlertas(Long usuarioId) {
        return alertaRepository.findByUsuarioIdOrderByCreatedAtDesc(usuarioId)
                .stream()
                .map(this::aAlertaResponse)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Cierre (RF-14)
    // ---------------------------------------------------------------------

    /**
     * Marca la alerta como ENCONTRADA y la retira del mapa (RF-14).
     * Solo puede cerrarla quien la publicó.
     */
    @Transactional
    public AlertaResponse cerrar(Long alertaId, Long usuarioId) {
        Alerta alerta = alertaRepository.findById(alertaId)
                .orElseThrow(() -> new ResourceNotFoundException("La alerta no existe"));

        if (!alerta.getUsuario().getId().equals(usuarioId)) {
            throw new AccessDeniedException("Solo quien publicó la alerta puede cerrarla");
        }

        alerta.setEstado(EstadoAlerta.ENCONTRADA);
        Alerta cerrada = alertaRepository.save(alerta);

        // El cliente necesita saber que debe retirar el marcador del mapa.
        publicarEnMapa(EventoMapaResponse.Accion.CIERRE, cerrada);

        return aAlertaResponse(cerrada);
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /**
     * Emite el evento en el canal del mapa. Un fallo de la mensajería no debe
     * tumbar la operación: la alerta ya está guardada y es lo que importa; el
     * cliente la vería igualmente al recargar el mapa.
     */
    private void publicarEnMapa(EventoMapaResponse.Accion accion, Alerta alerta) {
        if (alerta.getUltimaUbicacion() == null) {
            return;
        }
        try {
            mensajeria.convertAndSend(CANAL_MAPA, EventoMapaResponse.builder()
                    .accion(accion)
                    .alerta(aAlertaMapaResponse(alerta))
                    .build());
        } catch (RuntimeException e) {
            log.error("No se pudo emitir el evento {} de la alerta {} en {}",
                    accion, alerta.getId(), CANAL_MAPA, e);
        }
    }

    /** El orden de JTS es (x, y), es decir (longitud, latitud), no al revés. */
    private Point aPunto(UbicacionRequest ubicacion) {
        Point punto = geometryFactory.createPoint(
                new Coordinate(ubicacion.getLongitud(), ubicacion.getLatitud()));
        punto.setSRID(SRID_WGS84);
        return punto;
    }

    private UbicacionResponse aUbicacion(Point punto) {
        if (punto == null) {
            return null;
        }
        return UbicacionResponse.builder()
                .latitud(punto.getY())
                .longitud(punto.getX())
                .build();
    }

    private AlertaResponse aAlertaResponse(Alerta alerta) {
        return AlertaResponse.builder()
                .id(alerta.getId())
                .descripcion(alerta.getDescripcion())
                .fotoUrl(alerta.getFotoUrl())
                .especie(alerta.getEspecie())
                .fechaAvistamiento(alerta.getFechaAvistamiento())
                .ubicacion(aUbicacion(alerta.getUltimaUbicacion()))
                .radioBusqueda(alerta.getRadioBusqueda())
                .estado(alerta.getEstado())
                .createdAt(alerta.getCreatedAt())
                .autor(AutorResponse.builder()
                        .id(alerta.getUsuario().getId())
                        .nombre(alerta.getUsuario().getNombre())
                        .fotoUrl(alerta.getUsuario().getFotoUrl())
                        .build())
                .build();
    }

    private AlertaMapaResponse aAlertaMapaResponse(Alerta alerta) {
        return AlertaMapaResponse.builder()
                .id(alerta.getId())
                .especie(alerta.getEspecie())
                .fotoUrl(alerta.getFotoUrl())
                .ubicacion(aUbicacion(alerta.getUltimaUbicacion()))
                .fechaAvistamiento(alerta.getFechaAvistamiento())
                .estado(alerta.getEstado())
                .build();
    }
}
