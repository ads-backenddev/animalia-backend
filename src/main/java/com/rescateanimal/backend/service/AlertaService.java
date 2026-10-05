package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.ActualizarAlertaRequest;
import com.rescateanimal.backend.dto.request.CrearAlertaRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.AlertaDetalleResponse;
import com.rescateanimal.backend.dto.response.AlertaDuplicadaResponse;
import com.rescateanimal.backend.dto.response.AlertaMapaResponse;
import com.rescateanimal.backend.dto.response.AlertaResponse;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.EventoMapaResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.UbicacionResponse;
import com.rescateanimal.backend.exception.AlertaDuplicadaException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AlertaSpec;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** Tope de tamaño de página del listado, el mismo que el del catálogo de adopción. */
    static final int MAXIMO_POR_PAGINA = 100;

    private final AlertaRepository alertaRepository;
    private final UsuarioRepository usuarioRepository;
    private final NotificacionService notificacionService;
    private final FotoService fotoService;
    private final AvistamientoService avistamientoService;
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
                         AvistamientoService avistamientoService,
                         SimpMessagingTemplate mensajeria,
                         @Value("${app.alertas.radio-duplicados-metros}") double radioDuplicadosMetros,
                         @Value("${app.alertas.ventana-duplicados-horas}") int ventanaDuplicadosHoras,
                         @Value("${app.alertas.radio-mapa-maximo-metros}") double radioMapaMaximoMetros) {
        this.alertaRepository = alertaRepository;
        this.usuarioRepository = usuarioRepository;
        this.notificacionService = notificacionService;
        this.fotoService = fotoService;
        this.avistamientoService = avistamientoService;
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

    /**
     * Listado paginado de alertas (GET /api/alertas), para navegar y buscar.
     *
     * QUÉ ESTADOS SE LISTAN lo decide este método, no el cliente: ACTIVA por
     * defecto y ENCONTRADA si se pide —los casos resueltos también interesan—.
     * RETIRADA y ELIMINADA no se navegan: una la quitó su dueño y la otra el
     * administrador, y listarlas desharía las dos decisiones. Siguen
     * respondiendo por su id en el detalle, con el cuerpo reducido.
     *
     * Los filtros —especie sin distinguir mayúsculas, zona como círculo— los
     * describe AlertaSpec. La zona se valida igual que en el mapa.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<AlertaResponse> listar(String especie, EstadoAlerta estado, Double latitud,
                                                 Double longitud, Double radioMetros, Pageable paginacion) {
        EstadoAlerta estadoListado = estado == null ? EstadoAlerta.ACTIVA : estado;
        boolean seNavega = switch (estadoListado) {
            case ACTIVA, ENCONTRADA -> true;
            case RETIRADA, ELIMINADA -> false;
        };
        if (!seNavega) {
            throw new ParametrosInvalidosException("Solo se pueden listar alertas ACTIVA o ENCONTRADA");
        }

        boolean conZona = latitud != null || longitud != null || radioMetros != null;
        if (conZona) {
            validarArea(latitud, longitud, radioMetros);
        }

        PageRequest peticion = PageRequest.of(
                Math.max(0, paginacion.getPageNumber()),
                Math.min(Math.max(1, paginacion.getPageSize()), MAXIMO_POR_PAGINA),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<Alerta> pagina = alertaRepository.findAll(
                AlertaSpec.de(estadoListado, especie, latitud, longitud, radioMetros), peticion);
        return PaginaResponse.de(pagina, this::aAlertaResponse);
    }

    /**
     * Comprueba el filtro de área y devuelve el radio ya validado.
     *
     * NaN SE RECHAZA ANTES QUE NADA. Spring convierte "radio=NaN" en un Double
     * válido, y cualquier comparación con NaN da falso: pasaba los tres rangos
     * de abajo. PostgreSQL, en cambio, ordena NaN por encima de todo número, así
     * que "distancia <= NaN" es cierto para cualquier distancia y el listado se
     * saltaba el tope de radio. Los infinitos también se descartan aquí.
     */
    private double validarArea(Double latitud, Double longitud, Double radioMetros) {
        if (latitud == null || longitud == null || radioMetros == null) {
            throw new ParametrosInvalidosException(
                    "Los parámetros lat, lng y radio deben indicarse los tres o ninguno");
        }
        if (!Double.isFinite(latitud) || !Double.isFinite(longitud) || !Double.isFinite(radioMetros)) {
            throw new ParametrosInvalidosException("Los parámetros lat, lng y radio deben ser números finitos");
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

    /**
     * Detalle de una alerta con sus avistamientos (GET /api/alertas/{id}).
     *
     * 200 PARA CUALQUIER ALERTA QUE HAYA EXISTIDO, sea cual sea su estado; 404
     * solo para un id que nunca existió. Las alertas no se borran, así que
     * "terminada" y "no encontrada" son hechos distintos, y el cliente necesita
     * distinguirlos: un aviso ALERTA_CERCANA cuya alerta se cerró es un aviso
     * viejo, uno que da 404 es un aviso huérfano (docs/api-rest.md §12).
     *
     * LO RETIRADO RESPONDE, PERO NO SE VUELVE A PUBLICAR. Una RETIRADA la quitó
     * su dueño y una ELIMINADA el administrador; devolverlas enteras desharía las
     * dos decisiones para cualquiera que tenga el id. Su detalle lleva solo lo
     * que dice qué fue y en qué quedó: id, especie, estado y fecha.
     */
    @Transactional(readOnly = true)
    public AlertaDetalleResponse detalle(Long alertaId) {
        Alerta alerta = alertaRepository.findById(alertaId)
                .orElseThrow(() -> new ResourceNotFoundException("La alerta no existe"));

        return switch (alerta.getEstado()) {
            case ACTIVA, ENCONTRADA -> detalleCompleto(alerta);
            case RETIRADA, ELIMINADA -> detalleDeRetirada(alerta);
        };
    }

    private AlertaDetalleResponse detalleCompleto(Alerta alerta) {
        AlertaResponse base = aAlertaResponse(alerta);
        return AlertaDetalleResponse.builder()
                .id(base.getId())
                .descripcion(base.getDescripcion())
                .fotoUrl(base.getFotoUrl())
                .especie(base.getEspecie())
                .fechaAvistamiento(base.getFechaAvistamiento())
                .ubicacion(base.getUbicacion())
                .radioBusqueda(base.getRadioBusqueda())
                .estado(base.getEstado())
                .createdAt(base.getCreatedAt())
                .autor(base.getAutor())
                .avistamientos(avistamientoService.visiblesDeLaAlerta(alerta.getId()))
                .build();
    }

    private static AlertaDetalleResponse detalleDeRetirada(Alerta alerta) {
        return AlertaDetalleResponse.builder()
                .id(alerta.getId())
                .especie(alerta.getEspecie())
                .estado(alerta.getEstado())
                .createdAt(alerta.getCreatedAt())
                .avistamientos(List.of())
                .build();
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
    // Edición
    // ---------------------------------------------------------------------

    /**
     * Edita una alerta propia (PUT /api/alertas/{id}). Las comprobaciones van en
     * este orden: existe (404), es suya (403), sigue ACTIVA (400), la foto nueva
     * es suya (400) y no duplica a otra (409).
     *
     * SOLO MIENTRAS ESTÉ ACTIVA. Una alerta cerrada, retirada o moderada ya no
     * busca a nadie; editarla cambiaría lo que vieron quienes la siguieron, y en
     * una ELIMINADA serviría para colar contenido nuevo tras la moderación.
     *
     * RF-25 SE VUELVE A PASAR SI CAMBIAN LA ESPECIE O LA UBICACIÓN, y solo
     * entonces. Sin ello, la detección se esquivaría en dos pasos: publicar lejos
     * y mover después la alerta encima de otra, o publicar con otra especie y
     * cambiarla luego. No hace falta ningún umbral de "cambio significativo": la
     * propia detección ya tiene su tolerancia. Los demás campos —la fecha
     * incluida— no deciden si esta alerta duplica a otra, porque la ventana mira
     * las fechas de la existente, no las de esta (ver buscarSimilar). Al volver a
     * pasarla, la alerta no se cuenta a sí misma.
     *
     * La foto solo se comprueba si cambia, como en el perfil y en los animales:
     * reenviar la que ya tenía no debe fallar. La anterior, ya sin uso, la recoge
     * la limpieza diaria de fotos.
     *
     * Avisa al mapa con ACTUALIZACION, que mueve el marcador si cambió la
     * ubicación. No vuelve a avisar a la zona (RF-05): el aviso corresponde a la
     * publicación, y repetirlo en cada corrección sería ruido.
     */
    @Transactional
    public AlertaResponse actualizar(Long alertaId, ActualizarAlertaRequest peticion, Long usuarioId) {
        Alerta alerta = exigirAlertaPropia(alertaId, usuarioId, "Solo quien publicó la alerta puede editarla");

        boolean editable = switch (alerta.getEstado()) {
            case ACTIVA -> true;
            case ENCONTRADA, RETIRADA, ELIMINADA -> false;
        };
        if (!editable) {
            throw new OperacionNoPermitidaException("ALERTA_NO_ACTIVA",
                    "La alerta ya no está activa: no se puede editar");
        }

        String fotoUrl = StringUtils.hasText(peticion.getFotoUrl()) ? peticion.getFotoUrl().strip() : null;
        if (fotoUrl != null && !fotoUrl.equals(alerta.getFotoUrl())) {
            fotoService.exigirPropia(fotoUrl, usuarioId);
        }

        Point ubicacion = aPunto(peticion.getUbicacion());
        boolean cambiaEspecie = !peticion.getEspecie().equalsIgnoreCase(alerta.getEspecie());
        boolean cambiaUbicacion = alerta.getUltimaUbicacion() == null
                || !ubicacion.equalsExact(alerta.getUltimaUbicacion());
        if (cambiaEspecie || cambiaUbicacion) {
            comprobarQueNoEsDuplicada(peticion.getEspecie(), peticion.getUbicacion(), alerta.getId());
        }

        alerta.setDescripcion(peticion.getDescripcion());
        alerta.setFotoUrl(fotoUrl);
        alerta.setEspecie(peticion.getEspecie());
        alerta.setFechaAvistamiento(peticion.getFechaAvistamiento());
        alerta.setUltimaUbicacion(ubicacion);
        alerta.setRadioBusqueda(peticion.getRadioBusqueda());

        publicarEnMapa(EventoMapaResponse.Accion.ACTUALIZACION, alerta);
        log.info("Alerta {} editada por su autor", alertaId);
        return aAlertaResponse(alerta);
    }

    // ---------------------------------------------------------------------
    // Retirada por el dueño
    // ---------------------------------------------------------------------

    /**
     * Retira una alerta propia (DELETE /api/alertas/{id}): pasa a RETIRADA.
     *
     * NO BORRA NADA. Ni la alerta ni lo que cuelga de ella: sus avistamientos,
     * los matches sobre ellos, sus chats y sus mensajes siguen ahí, porque la
     * conversación pertenece también a la otra parte. Basta con el estado: el
     * feed y los matches ya solo admiten avistamientos de alertas ACTIVA, así
     * que los de esta salen del feed sin tocarlos.
     *
     * Se puede retirar una ACTIVA o una ENCONTRADA —el dueño puede no querer que
     * su caso resuelto se siga listando—. Si estaba en el mapa, se emite CIERRE.
     * Repetirlo no cambia nada, y sobre una ELIMINADA tampoco: responde igual,
     * pero la marca de moderación se queda, porque el dueño nunca sobrescribe la
     * decisión del administrador.
     */
    @Transactional
    public void retirar(Long alertaId, Long usuarioId) {
        Alerta alerta = exigirAlertaPropia(alertaId, usuarioId, "Solo quien publicó la alerta puede retirarla");

        boolean cambia = switch (alerta.getEstado()) {
            case ACTIVA, ENCONTRADA -> true;
            case RETIRADA, ELIMINADA -> false;
        };
        if (!cambia) {
            log.debug("La alerta {} ya estaba {}: retirarla no cambia nada", alertaId, alerta.getEstado());
            return;
        }

        boolean estabaEnElMapa = seVeEnElMapa(alerta.getEstado());
        alerta.setEstado(EstadoAlerta.RETIRADA);
        log.info("Alerta {} retirada por su autor", alertaId);
        if (estabaEnElMapa) {
            publicarEnMapa(EventoMapaResponse.Accion.CIERRE, alerta);
        }
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

    // ---------------------------------------------------------------------
    // Moderación (RF-11)
    // ---------------------------------------------------------------------

    /**
     * Retira una alerta por incumplir las normas: pasa a ELIMINADA. La llama
     * AdminService, que decide qué se modera; aquí vive porque cambiar el estado
     * de una alerta tiene consecuencias que solo conoce este service —el mapa—,
     * como pasa con AnimalAdopcionService.retirarTodosLosDisponibles.
     *
     * SI ESTABA EN EL MAPA, SE EMITE CIERRE. Antes la retirada vivía en
     * AdminService y no lo hacía: el marcador de una alerta moderada seguía en
     * el mapa de quien lo tuviera abierto hasta que recargara.
     *
     * Una RETIRADA por su dueño también pasa a ELIMINADA: la sanción es la
     * información más fuerte de las dos y no debe perderse. Ninguna fila se
     * borra (ver AdminService.retirarPublicacion).
     *
     * Lee la fila bloqueada, como las operaciones del dueño: si el dueño está
     * editando la alerta en ese momento, la moderación espera a que confirme y
     * se aplica encima; si es al revés, el dueño ve ya la ELIMINADA.
     *
     * @return true si esta llamada la retiró, false si ya estaba ELIMINADA
     */
    @Transactional
    public boolean retirarPorModeracion(Long alertaId, Long adminId) {
        Alerta alerta = alertaRepository.bloquearParaModificar(alertaId)
                .orElseThrow(() -> new ResourceNotFoundException("La alerta " + alertaId + " no existe"));

        boolean cambia = switch (alerta.getEstado()) {
            case ACTIVA, ENCONTRADA, RETIRADA -> true;
            case ELIMINADA -> false;
        };
        if (!cambia) {
            return false;
        }

        boolean estabaEnElMapa = seVeEnElMapa(alerta.getEstado());
        alerta.setEstado(EstadoAlerta.ELIMINADA);
        log.warn("El administrador {} ha retirado la alerta {} por incumplir las normas", adminId, alertaId);
        if (estabaEnElMapa) {
            publicarEnMapa(EventoMapaResponse.Accion.CIERRE, alerta);
        }
        return true;
    }

    /** Solo una ACTIVA tiene marcador en el mapa (RF-04). */
    private static boolean seVeEnElMapa(EstadoAlerta estado) {
        return switch (estado) {
            case ACTIVA -> true;
            case ENCONTRADA, RETIRADA, ELIMINADA -> false;
        };
    }

    /**
     * La alerta, si existe (404) y si es de este usuario (403), con su fila
     * bloqueada hasta el commit: quien llama va a decidir según su estado y a
     * escribirlo, y el administrador no debe poder moderarla entre medias (ver
     * AlertaRepository.bloquearParaModificar).
     */
    private Alerta exigirAlertaPropia(Long alertaId, Long usuarioId, String mensajeSiAjena) {
        Alerta alerta = alertaRepository.bloquearParaModificar(alertaId)
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
