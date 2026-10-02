package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.AvistamientoFeedResponse;
import com.rescateanimal.backend.dto.response.AvistamientoResponse;
import com.rescateanimal.backend.dto.response.UbicacionResponse;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lógica de registro de avistamientos y del feed de swipe (RF-15, RF-21).
 */
@Slf4j
@Service
public class AvistamientoService {

    /** SRID 4326: coordenadas geográficas WGS84, las mismas que da el GPS. */
    private static final int SRID_WGS84 = 4326;

    private final AvistamientoRepository avistamientoRepository;
    private final AlertaRepository alertaRepository;
    private final UsuarioRepository usuarioRepository;
    private final FotoService fotoService;

    private final int tamanyoFeed;

    private final GeometryFactory geometryFactory =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    public AvistamientoService(AvistamientoRepository avistamientoRepository,
                               AlertaRepository alertaRepository,
                               UsuarioRepository usuarioRepository,
                               FotoService fotoService,
                               @Value("${app.avistamientos.tamanyo-feed}") int tamanyoFeed) {
        this.avistamientoRepository = avistamientoRepository;
        this.alertaRepository = alertaRepository;
        this.usuarioRepository = usuarioRepository;
        this.fotoService = fotoService;
        this.tamanyoFeed = tamanyoFeed;
    }

    // ---------------------------------------------------------------------
    // Registro (RF-21)
    // ---------------------------------------------------------------------

    /**
     * Registra un avistamiento sobre una alerta abierta (RF-21).
     *
     * La alerta tiene que estar ACTIVA: sobre una que ya se cerró porque el
     * animal apareció, un avistamiento nuevo no aporta nada y ademas ensuciaría
     * el feed de swipe con tarjetas muertas.
     */
    @Transactional
    public AvistamientoResponse registrar(CrearAvistamientoRequest peticion, Long autorId) {
        String fotoUrl = peticion.getFotoUrl().strip();
        fotoService.exigirPropia(fotoUrl, autorId);

        Alerta alerta = alertaRepository.findById(peticion.getAlertaId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La alerta " + peticion.getAlertaId() + " no existe"));

        if (alerta.getEstado() != EstadoAlerta.ACTIVA) {
            throw new OperacionNoPermitidaException("ALERTA_NO_ACTIVA",
                    "La alerta ya no está activa: no admite avistamientos nuevos");
        }

        Usuario autor = usuarioRepository.findById(autorId)
                .orElseThrow(() -> new ResourceNotFoundException("El usuario no existe"));

        Avistamiento avistamiento = new Avistamiento();
        avistamiento.setUsuario(autor);
        avistamiento.setAlerta(alerta);
        avistamiento.setDescripcion(peticion.getDescripcion());
        avistamiento.setFotoUrl(fotoUrl);
        avistamiento.setUbicacion(aPunto(peticion.getUbicacion()));
        // Nace visible; solo el administrador lo retira (RF-11). El estado se
        // fija aquí y no con un DEFAULT en la columna para que la decisión viva
        // en un solo sitio.
        avistamiento.setEstado(EstadoAvistamiento.VISIBLE);

        Avistamiento guardado = avistamientoRepository.save(avistamiento);
        log.info("Avistamiento {} registrado sobre la alerta {}", guardado.getId(), alerta.getId());

        return aAvistamientoResponse(guardado);
    }

    // ---------------------------------------------------------------------
    // Feed de swipe (RF-15)
    // ---------------------------------------------------------------------

    /**
     * Avistamientos que el usuario todavía no ha deslizado (RF-15).
     *
     * Se devuelve una tanda acotada, no todo lo que haya: el feed se consume de
     * uno en uno y pedir el catálogo entero no tendría sentido.
     *
     * PENDIENTE: docs/api-rest.md pide ordenarlos por proximidad al usuario. Hoy
     * van por fecha, más recientes primero, porque la petición no trae la
     * posición del usuario y USUARIO no guarda coordenadas —areaGeografica es
     * texto—. Ordenar por cercanía exige antes decidir de dónde sale ese punto.
     */
    @Transactional(readOnly = true)
    public List<AvistamientoFeedResponse> feed(Long usuarioId) {
        return avistamientoRepository.buscarCandidatosParaFeed(
                        usuarioId,
                        EstadoAvistamiento.VISIBLE,
                        EstadoAlerta.ACTIVA,
                        TipoPublicacion.AVISTAMIENTO,
                        PageRequest.of(0, tamanyoFeed))
                .stream()
                .map(this::aFeedResponse)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

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

    /**
     * Los avistamientos VISIBLE de una alerta, para su detalle. Los ELIMINADO no
     * salen: los retiró el administrador por incumplir las normas, y el detalle
     * los volvería a publicar.
     */
    @Transactional(readOnly = true)
    public List<AvistamientoResponse> visiblesDeLaAlerta(Long alertaId) {
        return avistamientoRepository.buscarDeLaAlerta(alertaId, EstadoAvistamiento.VISIBLE)
                .stream()
                .map(this::aAvistamientoResponse)
                .toList();
    }

    private AvistamientoResponse aAvistamientoResponse(Avistamiento avistamiento) {
        return AvistamientoResponse.builder()
                .id(avistamiento.getId())
                .alertaId(avistamiento.getAlerta().getId())
                .descripcion(avistamiento.getDescripcion())
                .fotoUrl(avistamiento.getFotoUrl())
                .ubicacion(aUbicacion(avistamiento.getUbicacion()))
                .createdAt(avistamiento.getCreatedAt())
                .autor(aAutor(avistamiento.getUsuario()))
                .build();
    }

    private AvistamientoFeedResponse aFeedResponse(Avistamiento avistamiento) {
        Alerta alerta = avistamiento.getAlerta();
        return AvistamientoFeedResponse.builder()
                .id(avistamiento.getId())
                .descripcion(avistamiento.getDescripcion())
                .fotoUrl(avistamiento.getFotoUrl())
                .ubicacion(aUbicacion(avistamiento.getUbicacion()))
                .createdAt(avistamiento.getCreatedAt())
                .autor(aAutor(avistamiento.getUsuario()))
                .alertaId(alerta.getId())
                .alertaEspecie(alerta.getEspecie())
                .alertaFotoUrl(alerta.getFotoUrl())
                .build();
    }

    /** Sin email ni teléfono: son datos personales y el feed es contenido ajeno (RNF-10). */
    private AutorResponse aAutor(Usuario usuario) {
        return AutorResponse.builder()
                .id(usuario.getId())
                .nombre(usuario.getNombre())
                .fotoUrl(usuario.getFotoUrl())
                .build();
    }
}
