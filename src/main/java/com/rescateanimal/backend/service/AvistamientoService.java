package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.AvistamientoFeedResponse;
import com.rescateanimal.backend.dto.response.AvistamientoResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.UbicacionResponse;
import com.rescateanimal.backend.exception.LimiteAvistamientosAlcanzadoException;
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
import com.rescateanimal.backend.repository.LimiteAvistamientoDiarioRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lógica de registro de avistamientos, del feed de swipe del dueño de cada
 * alerta y de su lista de descartados (RF-15, RF-21).
 */
@Slf4j
@Service
public class AvistamientoService {

    /** SRID 4326: coordenadas geográficas WGS84, las mismas que da el GPS. */
    private static final int SRID_WGS84 = 4326;

    /** El día del cupo es el de Madrid, como el de matches y el de fotos. */
    private static final ZoneId ZONA_CUPO = ZoneId.of("Europe/Madrid");

    /** Tope de tamaño de página de los descartados, el mismo que el del resto de listados. */
    static final int MAXIMO_POR_PAGINA = 100;

    private final AvistamientoRepository avistamientoRepository;
    private final AlertaRepository alertaRepository;
    private final UsuarioRepository usuarioRepository;
    private final FotoService fotoService;
    private final NotificacionService notificacionService;
    private final LimiteAvistamientoDiarioRepository limiteAvistamientoDiarioRepository;

    private final int tamanyoFeed;
    private final int limiteDiarioPorAlerta;

    private final GeometryFactory geometryFactory =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    public AvistamientoService(AvistamientoRepository avistamientoRepository,
                               AlertaRepository alertaRepository,
                               UsuarioRepository usuarioRepository,
                               FotoService fotoService,
                               NotificacionService notificacionService,
                               LimiteAvistamientoDiarioRepository limiteAvistamientoDiarioRepository,
                               @Value("${app.avistamientos.tamanyo-feed}") int tamanyoFeed,
                               @Value("${app.avistamientos.limite-diario-por-alerta}") int limiteDiarioPorAlerta) {
        this.avistamientoRepository = avistamientoRepository;
        this.alertaRepository = alertaRepository;
        this.usuarioRepository = usuarioRepository;
        this.fotoService = fotoService;
        this.notificacionService = notificacionService;
        this.limiteAvistamientoDiarioRepository = limiteAvistamientoDiarioRepository;
        this.tamanyoFeed = tamanyoFeed;
        this.limiteDiarioPorAlerta = limiteDiarioPorAlerta;
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
     *
     * LA ALERTA SE LEE CON BLOQUEO COMPARTIDO hasta el commit. Sin él, una
     * retirada o una moderación que confirmara entre la comprobación de ACTIVA y
     * el INSERT dejaba un avistamiento colgando de una alerta terminada. Es
     * compartido para que los avistamientos de una misma alerta no se esperen
     * entre sí (ver AlertaRepository.bloquearParaAvistar).
     *
     * HAY UN CUPO DIARIO POR USUARIO Y ALERTA, cinco por defecto
     * (app.avistamientos.limite-diario-por-alerta), con el mismo mecanismo
     * atómico y el mismo día natural de Europe/Madrid que los de matches y
     * fotos. Sin él, un
     * usuario podía enterrar el feed del dueño —y llenarle de avisos— con
     * cientos de avistamientos falsos. Se consume después de saber que la
     * alerta existe y sigue ACTIVA, porque la fila del cupo apunta a ella, y
     * antes del INSERT; como va en la misma transacción, un registro que acaba
     * fallando deshace su consumo.
     *
     * ORDEN DE BLOQUEO: la alerta (FOR SHARE) y después la fila del cupo de este
     * usuario en esta alerta, con el UPDATE condicional. Los avistamientos de
     * personas distintas sobre una misma alerta siguen sin esperarse —cada una
     * tiene su fila—; dos de la misma persona a la vez sí se esperan en la suya,
     * que es justo lo que impide pasarse del límite.
     */
    @Transactional
    public AvistamientoResponse registrar(CrearAvistamientoRequest peticion, Long autorId) {
        String fotoUrl = peticion.getFotoUrl().strip();
        fotoService.exigirPropia(fotoUrl, autorId);

        Alerta alerta = alertaRepository.bloquearParaAvistar(peticion.getAlertaId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La alerta " + peticion.getAlertaId() + " no existe"));

        if (alerta.getEstado() != EstadoAlerta.ACTIVA) {
            throw new OperacionNoPermitidaException("ALERTA_NO_ACTIVA",
                    "La alerta ya no está activa: no admite avistamientos nuevos");
        }

        consumirCupo(autorId, alerta.getId());

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

        // El dueño se entera de que tiene algo que revisar en su feed. El aviso
        // se crea tras el commit: si el avistamiento se deshiciera, no habría
        // nada que revisar.
        notificacionService.avisarNuevoAvistamiento(alerta.getId(), alerta.getUsuario().getId(), autorId);

        return aAvistamientoResponse(guardado);
    }

    // ---------------------------------------------------------------------
    // Feed de swipe del dueño (RF-15)
    // ---------------------------------------------------------------------

    /**
     * Avistamientos de una alerta que su dueño todavía no ha revisado
     * (GET /api/alertas/{id}/avistamientos/feed).
     *
     * Es el único feed de avistamientos. El que había para cualquier usuario,
     * con los de todas las alertas activas, se retiró: con el match reservado
     * al dueño, cada tarjeta suya habría acabado en un 403.
     *
     * Se devuelve una tanda acotada, no todo lo que haya: el feed se consume de
     * uno en uno y pedirlo entero no tendría sentido.
     *
     * EL FEED ES DEL DUEÑO. Él es quien puede reconocer a su animal en la foto,
     * y quien abre el chat con el testigo si lo reconoce; nadie más hace match
     * sobre los avistamientos de una alerta ajena (ver MatchService).
     *
     * Las comprobaciones van en el orden de siempre: 404 si la alerta no
     * existe, 403 si no es suya y 400 si ya no está ACTIVA —sobre un animal que
     * ya apareció no hay nada que revisar—. Solo lee, así que no bloquea la
     * alerta: si se cierra mientras tanto, el match sobre la tarjeta es lo que
     * lo rechaza.
     *
     * MÁS RECIENTES PRIMERO, no por cercanía. Lo que el dueño quiere saber es
     * dónde está el animal ahora, y eso lo dice el último avistamiento. La
     * última ubicación de la alerta no se mueve con los avistamientos —es dónde
     * se perdió—, así que ordenar por cercanía a ella dejaría al final justo los
     * avistamientos que muestran que se ha alejado. La distancia viaja en cada
     * tarjeta como información, sin decidir el orden.
     */
    @Transactional(readOnly = true)
    public List<AvistamientoFeedResponse> feedDeLaAlerta(Long alertaId, Long usuarioId) {
        exigirAlertaActivaDelDueno(alertaId, usuarioId);

        return aTarjetas(avistamientoRepository.buscarPendientesDeRevisar(
                alertaId,
                usuarioId,
                EstadoAvistamiento.VISIBLE,
                TipoPublicacion.AVISTAMIENTO,
                PageRequest.of(0, tamanyoFeed)));
    }

    /**
     * Los avistamientos de una alerta que su dueño descartó
     * (GET /api/alertas/{id}/avistamientos/descartados), para que recupere uno
     * que descartó por error: hace match desde aquí con el mismo
     * POST /api/matches, que convierte el descarte (MatchService.deslizar).
     *
     * Las comprobaciones son las del feed, en el mismo orden, y la tarjeta la
     * misma, distancia incluida. Cambian el orden —el último descarte primero—
     * y que va en páginas (ver AvistamientoRepository.buscarDescartados). El
     * orden lo fija la consulta: el que pida el cliente no se aplica.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<AvistamientoFeedResponse> descartadosDeLaAlerta(Long alertaId, Long usuarioId,
                                                                          Pageable paginacion) {
        exigirAlertaActivaDelDueno(alertaId, usuarioId);

        PageRequest peticion = Paginas.acotada(paginacion, MAXIMO_POR_PAGINA);
        Page<Avistamiento> pagina = avistamientoRepository.buscarDescartados(
                alertaId, usuarioId, EstadoAvistamiento.VISIBLE, TipoPublicacion.AVISTAMIENTO, peticion);
        return PaginaResponse.de(pagina, aTarjetas(pagina.getContent()));
    }

    /**
     * La alerta tiene que existir (404), ser de este usuario (403) y seguir
     * ACTIVA (400), en ese orden: el permiso va antes que el estado. Es lo que
     * piden el feed y los descartados; sobre un animal que ya apareció no hay
     * nada que revisar ni que recuperar.
     */
    private void exigirAlertaActivaDelDueno(Long alertaId, Long usuarioId) {
        Alerta alerta = alertaRepository.findById(alertaId)
                .orElseThrow(() -> new ResourceNotFoundException("La alerta no existe"));
        if (!alerta.getUsuario().getId().equals(usuarioId)) {
            throw new AccessDeniedException("Solo quien publicó la alerta puede revisar sus avistamientos");
        }
        if (alerta.getEstado() != EstadoAlerta.ACTIVA) {
            throw new OperacionNoPermitidaException("ALERTA_NO_ACTIVA",
                    "La alerta ya no está activa: no hay avistamientos que revisar");
        }
    }

    /** Las tarjetas de una tanda, con su distancia resuelta en una sola consulta. */
    private List<AvistamientoFeedResponse> aTarjetas(List<Avistamiento> avistamientos) {
        if (avistamientos.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> distancias = new HashMap<>();
        avistamientoRepository.distanciasALaAlerta(avistamientos.stream().map(Avistamiento::getId).toList())
                .forEach(fila -> distancias.put(fila.getAvistamientoId(), Math.round(fila.getMetros())));

        return avistamientos.stream()
                .map(avistamiento -> aFeedResponse(avistamiento, distancias.get(avistamiento.getId())))
                .toList();
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /**
     * Suma un avistamiento al cupo del día de este usuario en esta alerta, o
     * falla con 429. Las dos sentencias son atómicas en la base de datos: ver
     * LimiteAvistamientoDiarioRepository.
     */
    private void consumirCupo(Long autorId, Long alertaId) {
        LocalDate hoy = LocalDate.now(ZONA_CUPO);
        limiteAvistamientoDiarioRepository.crearContadorSiNoExiste(autorId, alertaId, hoy);
        if (limiteAvistamientoDiarioRepository.incrementarSiHayCupo(
                autorId, alertaId, hoy, limiteDiarioPorAlerta) == 0) {
            log.info("Usuario {} ha agotado sus {} avistamientos del día en la alerta {}",
                    autorId, limiteDiarioPorAlerta, alertaId);
            // Medianoche de Madrid, como reiniciaEn en los otros dos cupos.
            throw new LimiteAvistamientosAlcanzadoException(limiteDiarioPorAlerta, hoy.plusDays(1).atStartOfDay());
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

    private AvistamientoFeedResponse aFeedResponse(Avistamiento avistamiento, Long distanciaMetros) {
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
                .distanciaMetros(distanciaMetros)
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
