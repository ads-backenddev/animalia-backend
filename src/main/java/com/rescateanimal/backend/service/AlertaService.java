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
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /** excluirId de buscarSimilar al publicar: ningún id de IDENTITY vale 0. */
    private static final long NINGUNA_ALERTA = 0L;

    private final AlertaRepository alertaRepository;
    private final UsuarioRepository usuarioRepository;
    private final NotificacionService notificacionService;
    private final FotoService fotoService;
    private final SimpMessagingTemplate mensajeria;

    private final double radioDuplicadosMetros;
    private final int ventanaDuplicadosHoras;

    /** Tope del filtro de área del mapa: sin él, "radio=20000000" lo pide todo. */
    private final double radioMapaMaximoMetros;

    /** Un GeometryFactory es inmutable y seguro entre hilos: basta con uno. */
    private final GeometryFactory geometryFactory =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    public AlertaService(AlertaRepository alertaRepository,
                         UsuarioRepository usuarioRepository,
                         NotificacionService notificacionService,
                         FotoService fotoService,
                         SimpMessagingTemplate mensajeria,
                         @Value("${app.alertas.radio-duplicados-metros}") double radioDuplicadosMetros,
                         @Value("${app.alertas.ventana-duplicados-horas}") int ventanaDuplicadosHoras,
                         @Value("${app.alertas.radio-mapa-maximo-metros}") double radioMapaMaximoMetros) {
        this.alertaRepository = alertaRepository;
        this.usuarioRepository = usuarioRepository;
        this.notificacionService = notificacionService;
        this.fotoService = fotoService;
        this.mensajeria = mensajeria;
        this.radioDuplicadosMetros = radioDuplicadosMetros;
        this.ventanaDuplicadosHoras = ventanaDuplicadosHoras;
        this.radioMapaMaximoMetros = radioMapaMaximoMetros;
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
        // La foto es opcional, y una cadena en blanco equivale a no mandarla.
        String fotoUrl = StringUtils.hasText(peticion.getFotoUrl()) ? peticion.getFotoUrl().strip() : null;
        if (fotoUrl != null) {
            fotoService.exigirPropia(fotoUrl, autorId);
        }

        comprobarQueNoEsDuplicada(peticion.getEspecie(), peticion.getUbicacion(), NINGUNA_ALERTA);

        Usuario autor = usuarioRepository.findById(autorId)
                .orElseThrow(() -> new ResourceNotFoundException("El usuario no existe"));

        Alerta alerta = new Alerta();
        alerta.setUsuario(autor);
        alerta.setDescripcion(peticion.getDescripcion());
        alerta.setFotoUrl(fotoUrl);
        alerta.setEspecie(peticion.getEspecie());
        alerta.setFechaAvistamiento(peticion.getFechaAvistamiento());
        alerta.setUltimaUbicacion(aPunto(peticion.getUbicacion()));
        alerta.setRadioBusqueda(peticion.getRadioBusqueda());
        alerta.setEstado(EstadoAlerta.ACTIVA);

        Alerta guardada = alertaRepository.save(alerta);

        // Efectos derivados de la publicación: aviso a la zona (RF-05) y refresco
        // del mapa de quienes lo tengan abierto (RF-04). Ninguno de los dos puede
        // tumbar la publicación: la alerta ya está guardada y es lo que importa.
        notificarZona(guardada);
        publicarEnMapa(EventoMapaResponse.Accion.ALTA, guardada);

        return aAlertaResponse(guardada);
    }

    /**
     * Detección de alertas duplicadas (RF-25): misma especie, radio de 500 m y
     * ventana de 24 h, los tres a la vez. La ventana mira cuándo se publicó la
     * alerta existente además de cuándo se vio al animal: el porqué está en
     * AlertaRepository.buscarSimilar.
     *
     * Sirve a la publicación y a la edición. Al editar, excluirId deja fuera a
     * la propia alerta, que si no chocaría consigo misma; al publicar se pasa
     * NINGUNA_ALERTA.
     *
     * LA VENTANA SON 24 HORAS REALES. Se calcula restando sobre un Instant, no
     * sobre un LocalDateTime como antes, y eso arregla dos cosas distintas:
     *
     * 1. Deja de depender de la zona horaria de la JVM. LocalDateTime.now()
     *    daba las 12:00 en el portátil de desarrollo (Europe/Madrid) y las
     *    10:00 en Railway (UTC) en el mismo momento, así que la misma alerta
     *    entraba o no en la ventana según dónde corriera la API.
     * 2. Resta tiempo, no reloj. minusHours(24) sobre una hora de pared son 24
     *    horas de calendario: la noche del cambio de hora eso son 23 o 25 horas
     *    de verdad, y la ventana se encogía o se estiraba una hora al año.
     *
     * LIMITACIÓN CONOCIDA: comprobar y guardar no son atómicos. Dos peticiones
     * simultáneas sobre la misma zona pueden pasar las dos la comprobación antes
     * de que ninguna confirme, y publicarse ambas. Cerrarlo exige un bloqueo
     * consultivo de PostgreSQL por especie y zona, o una restricción en la propia
     * base de datos; se ha dejado fuera porque el escenario —dos vecinos dando de
     * alta el mismo animal en el mismo segundo— es poco probable y el coste de
     * fallar es una alerta repetida, no un dato corrupto.
     */
    private void comprobarQueNoEsDuplicada(String especie, UbicacionRequest ubicacion, long excluirId) {
        Instant desde = Instant.now().minus(ventanaDuplicadosHoras, ChronoUnit.HOURS);
        double latitud = ubicacion.getLatitud();
        double longitud = ubicacion.getLongitud();

        Optional<Alerta> similar = alertaRepository.buscarSimilar(
                especie, latitud, longitud, radioDuplicadosMetros, desde, excluirId);

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

    /**
     * Alertas activas y geolocalizadas, para pintar el mapa interactivo (RF-04).
     *
     * Los tres parámetros acotan el mapa al área que el usuario está viendo y van
     * juntos o no van: con solo dos de ellos no hay círculo que consultar. Sin
     * ellos se devuelven todas las alertas activas, que es lo razonable mientras
     * el volumen sea pequeño pero deja de serlo a escala nacional; por eso el
     * cliente debería mandarlos siempre.
     */
    @Transactional(readOnly = true)
    public List<AlertaMapaResponse> listarParaMapa(Double latitud, Double longitud, Double radioMetros) {
        List<Alerta> alertas;
        if (latitud == null && longitud == null && radioMetros == null) {
            alertas = alertaRepository.findByEstadoAndUltimaUbicacionIsNotNull(EstadoAlerta.ACTIVA);
        } else {
            // Validar antes de llamar, no dentro de la llamada: Java evalúa los
            // argumentos de izquierda a derecha, así que un lat o lng nulo
            // reventaría al desempaquetarlo antes de llegar a la validación.
            double radio = validarArea(latitud, longitud, radioMetros);
            alertas = alertaRepository.buscarActivasCerca(latitud, longitud, radio);
        }

        return alertas.stream().map(this::aAlertaMapaResponse).toList();
    }

    /** Comprueba el filtro de área y devuelve el radio ya validado. */
    private double validarArea(Double latitud, Double longitud, Double radioMetros) {
        if (latitud == null || longitud == null || radioMetros == null) {
            throw new ParametrosInvalidosException(
                    "Los parámetros lat, lng y radio deben indicarse los tres o ninguno");
        }
        if (latitud < -90 || latitud > 90) {
            throw new ParametrosInvalidosException("La latitud debe estar entre -90 y 90");
        }
        if (longitud < -180 || longitud > 180) {
            throw new ParametrosInvalidosException("La longitud debe estar entre -180 y 180");
        }
        if (radioMetros <= 0 || radioMetros > radioMapaMaximoMetros) {
            throw new ParametrosInvalidosException(
                    "El radio debe ser mayor que cero y como mucho " + radioMapaMaximoMetros + " metros");
        }
        return radioMetros;
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
     *
     * SOLO DESDE ACTIVA. Antes no se miraba el estado, y el dueño de una alerta
     * retirada por el administrador podía marcarla como ENCONTRADA y deshacer la
     * moderación: volvía a verse entera en el detalle y en el listado de
     * resueltas. Cerrar una ya ENCONTRADA no cambia nada y responde igual, como
     * cualquier repetición de la misma orden; una RETIRADA o ELIMINADA no se
     * puede cerrar, porque el animal ya no se está buscando a través de ella.
     */
    @Transactional
    public AlertaResponse cerrar(Long alertaId, Long usuarioId) {
        Alerta alerta = exigirAlertaPropia(alertaId, usuarioId, "Solo quien publicó la alerta puede cerrarla");

        boolean seCierraAhora = switch (alerta.getEstado()) {
            case ACTIVA -> true;
            case ENCONTRADA -> false;
            case RETIRADA, ELIMINADA -> throw new OperacionNoPermitidaException("ALERTA_NO_ACTIVA",
                    "La alerta ya no está activa: no se puede marcar como encontrada");
        };

        if (seCierraAhora) {
            alerta.setEstado(EstadoAlerta.ENCONTRADA);
            alerta = alertaRepository.save(alerta);
            // El cliente necesita saber que debe retirar el marcador del mapa.
            publicarEnMapa(EventoMapaResponse.Accion.CIERRE, alerta);
        }

        return aAlertaResponse(alerta);
    }

    /** La alerta, si existe (404) y si es de este usuario (403). */
    private Alerta exigirAlertaPropia(Long alertaId, Long usuarioId, String mensajeSiAjena) {
        Alerta alerta = alertaRepository.findById(alertaId)
                .orElseThrow(() -> new ResourceNotFoundException("La alerta no existe"));

        if (!alerta.getUsuario().getId().equals(usuarioId)) {
            throw new AccessDeniedException(mensajeSiAjena);
        }
        return alerta;
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /**
     * Avisa a la zona sin arriesgar la publicación.
     *
     * NotificacionService abre su propia transacción (REQUIRES_NEW), de modo que
     * un fallo suyo no marca como "rollback only" la transacción que acaba de
     * guardar la alerta; aquí solo queda registrarlo. Sin las dos piezas —la
     * transacción aparte y este catch— un error creando notificaciones haría
     * fracasar un RF-03 perfectamente válido por culpa de un RF-05.
     *
     * Lo contrario sí puede pasar: las notificaciones se confirman antes que la
     * alerta, y si la publicación fallara al confirmar quedarían huérfanas. Es
     * una limitación aceptada; el razonamiento está en
     * NotificacionService.notificarAlertaCercana.
     */
    private void notificarZona(Alerta alerta) {
        try {
            notificacionService.notificarAlertaCercana(alerta);
        } catch (RuntimeException e) {
            log.error("No se pudieron crear las notificaciones de la alerta {}", alerta.getId(), e);
        }
    }

    /**
     * Emite el evento en el canal del mapa, ya confirmada la transacción.
     *
     * El envío se aplaza al commit a propósito: enviándolo dentro de la
     * transacción, un cliente podía recibir el ALTA y pedir el mapa antes de que
     * la fila fuera visible para las demás conexiones, y ver un mapa sin la
     * alerta que le acababan de anunciar. Peor aún, si la transacción terminaba
     * en rollback el evento ya estaba emitido y no había forma de retirarlo.
     *
     * El DTO se construye ahora, con la entidad todavía gestionada, y no dentro
     * de la retrollamada, donde el contexto de persistencia ya se está cerrando.
     *
     * Un fallo de la mensajería tampoco debe tumbar la operación: la alerta ya
     * está guardada y el cliente la vería igualmente al recargar el mapa.
     */
    private void publicarEnMapa(EventoMapaResponse.Accion accion, Alerta alerta) {
        if (alerta.getUltimaUbicacion() == null) {
            return;
        }
        EventoMapaResponse evento = EventoMapaResponse.builder()
                .accion(accion)
                .alerta(aAlertaMapaResponse(alerta))
                .build();
        Long alertaId = alerta.getId();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enviarAlMapa(evento, accion, alertaId);
                }
            });
        } else {
            enviarAlMapa(evento, accion, alertaId);
        }
    }

    private void enviarAlMapa(EventoMapaResponse evento, EventoMapaResponse.Accion accion, Long alertaId) {
        try {
            mensajeria.convertAndSend(CANAL_MAPA, evento);
        } catch (RuntimeException e) {
            log.error("No se pudo emitir el evento {} de la alerta {} en {}",
                    accion, alertaId, CANAL_MAPA, e);
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
