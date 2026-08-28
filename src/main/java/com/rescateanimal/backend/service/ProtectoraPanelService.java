package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ResumenPanelResponse;
import com.rescateanimal.backend.dto.response.SolicitudAdopcionResponse;
import com.rescateanimal.backend.dto.response.ValoracionResponse;
import com.rescateanimal.backend.dto.response.ValoracionesPanelResponse;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.Valoracion;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.ValoracionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Panel de gestión de la protectora: sus animales, las solicitudes de adopción
 * recibidas, sus valoraciones y los contadores de la pantalla principal (RF-10).
 *
 * DOS NIVELES DE PERMISO, COMO EN EL CATÁLOGO. El rol PROTECTORA lo impone
 * @PreAuthorize en el controller. No basta: hace falta además que la protectora
 * esté ACTIVA, y eso no cabe en una anotación porque depende de una fila de la
 * base de datos que puede cambiar después de emitirse el token. Lo resuelve
 * exigirProtectoraActiva, por el que pasan los cuatro endpoints sin excepción.
 *
 * POR QUÉ HAY QUE VOLVER A COMPROBAR EL ESTADO AQUÍ. AuthService ya rechaza el
 * login de una protectora PENDIENTE o RECHAZADA, así que podría parecer que al
 * panel solo llegan las ACTIVA. No es cierto: el token dura horas y el estado
 * vive en la base de datos, de modo que una protectora que entra estando ACTIVA
 * y a la que el administrador rechaza a continuación (RF-11) sigue con un token
 * perfectamente válido en la mano. Sin esta comprobación seguiría gestionando su
 * panel hasta que el token caducara. El login filtra la puerta; esto filtra cada
 * petición.
 *
 * NO SE FÍA DE NINGÚN ID QUE VENGA DEL CLIENTE. Ningún endpoint del panel recibe
 * un id de protectora: todos parten del usuario autenticado y resuelven la suya.
 * Por eso "ver el panel de otra protectora" no es un caso que haya que rechazar,
 * es un caso que no se puede ni expresar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProtectoraPanelService {

    /**
     * Estado en el que una solicitud sigue requiriendo gestión.
     *
     * Mientras el animal está DISPONIBLE la protectora tiene algo que hacer con
     * quien lo pidió; en cuanto pasa a ADOPTADO o RETIRADO el caso está cerrado.
     * El razonamiento completo, y la alternativa descartada, están en el javadoc
     * de ResumenPanelResponse.solicitudesPendientes.
     */
    private static final EstadoAnimal ESTADO_SOLICITUD_PENDIENTE = EstadoAnimal.DISPONIBLE;

    private final ProtectoraRepository protectoraRepository;
    private final AnimalAdopcionRepository animalAdopcionRepository;
    private final MatchRegistroRepository matchRegistroRepository;
    private final ChatRepository chatRepository;
    private final ValoracionRepository valoracionRepository;
    private final AnimalAdopcionService animalAdopcionService;

    // ---------------------------------------------------------------------
    // Endpoints del panel (RF-10)
    // ---------------------------------------------------------------------

    /**
     * Todos los animales de la protectora, en cualquier estado (RF-10).
     *
     * Delega en AnimalAdopcionService en vez de consultar por su cuenta: es el
     * mismo catálogo con otra política de visibilidad, y tenerlo escrito dos
     * veces habría acabado con dos catálogos que se parecen.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<AnimalAdopcionResponse> animales(Long usuarioId, Pageable paginacion) {
        Protectora protectora = exigirProtectoraActiva(usuarioId);
        return animalAdopcionService.listarDeLaProtectora(protectora.getId(), paginacion);
    }

    /**
     * Ciudadanos que han querido ponerse en contacto por alguno de sus animales
     * (RF-10), más recientes primero.
     *
     * Solo matches, nunca descartes: lo garantiza la consulta, y es la
     * diferencia entre "alguien quiere a este animal" y "alguien pasó de largo".
     *
     * Las tres consultas son fijas, no crecen con el número de solicitudes: la
     * de solicitudes, una para todos los animales implicados y otra para todos
     * los chats. Resolverlos dentro del bucle habría dado dos consultas por
     * fila, que es el N+1 que ya se evitó en /api/matches/mios.
     */
    @Transactional(readOnly = true)
    public List<SolicitudAdopcionResponse> solicitudes(Long usuarioId) {
        Protectora protectora = exigirProtectoraActiva(usuarioId);

        List<MatchRegistro> solicitudes = matchRegistroRepository
                .buscarSolicitudesDeProtectora(protectora.getId(), TipoPublicacion.ADOPCION);

        if (solicitudes.isEmpty()) {
            return List.of();
        }

        Map<Long, AnimalAdopcion> animalPorId = animalAdopcionRepository
                .buscarConProtectoraPorIds(
                        solicitudes.stream().map(MatchRegistro::getPublicacionId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(AnimalAdopcion::getId, Function.identity()));

        Map<Long, Long> chatPorMatch = chatRepository
                .findByMatchIdIn(solicitudes.stream().map(MatchRegistro::getId).toList())
                .stream()
                .collect(Collectors.toMap(chat -> chat.getMatch().getId(), Chat::getId));

        return solicitudes.stream()
                .map(solicitud -> aSolicitudResponse(
                        solicitud,
                        animalPorId.get(solicitud.getPublicacionId()),
                        chatPorMatch.get(solicitud.getId())))
                .toList();
    }

    /**
     * Valoraciones recibidas, con la media ya calculada (RF-10, RF-23).
     *
     * La media se calcula sobre lo que se acaba de leer en vez de pedirla otra
     * vez a la base de datos: ya están todas en memoria, y así media y listado
     * no pueden discrepar.
     */
    @Transactional(readOnly = true)
    public ValoracionesPanelResponse valoraciones(Long usuarioId) {
        Protectora protectora = exigirProtectoraActiva(usuarioId);

        List<Valoracion> valoraciones =
                valoracionRepository.buscarDeProtectoraConAutor(protectora.getId());

        return ValoracionesPanelResponse.builder()
                .valoraciones(valoraciones.stream().map(ProtectoraPanelService::aValoracionResponse).toList())
                .media(mediaDe(valoraciones.stream()
                        .mapToInt(Valoracion::getPuntuacion)
                        .average()
                        .orElse(Double.NaN)))
                .total(valoraciones.size())
                .build();
    }

    /**
     * Contadores de la pantalla principal del panel (RF-10).
     *
     * Todo son agregaciones: no se trae ni un animal ni una solicitud a memoria.
     * Es el motivo de que este endpoint exista en vez de dejar que el cliente
     * pida los tres listados y los cuente.
     */
    @Transactional(readOnly = true)
    public ResumenPanelResponse resumen(Long usuarioId) {
        Protectora protectora = exigirProtectoraActiva(usuarioId);
        Long protectoraId = protectora.getId();

        Map<EstadoAnimal, Long> porEstado = new EnumMap<>(EstadoAnimal.class);
        animalAdopcionRepository.contarPorEstado(protectoraId)
                .forEach(fila -> porEstado.put(fila.getEstado(), fila.getTotal()));

        long disponibles = porEstado.getOrDefault(EstadoAnimal.DISPONIBLE, 0L);
        long adoptados = porEstado.getOrDefault(EstadoAnimal.ADOPTADO, 0L);
        long retirados = porEstado.getOrDefault(EstadoAnimal.RETIRADO, 0L);

        Double media = valoracionRepository.mediaDeProtectora(protectoraId);

        return ResumenPanelResponse.builder()
                .animalesDisponibles(disponibles)
                .animalesAdoptados(adoptados)
                .animalesRetirados(retirados)
                .animalesTotales(disponibles + adoptados + retirados)
                .solicitudesPendientes(matchRegistroRepository.contarSolicitudesPendientes(
                        protectoraId, TipoPublicacion.ADOPCION, ESTADO_SOLICITUD_PENDIENTE))
                .solicitudesTotales(matchRegistroRepository.contarSolicitudesDeProtectora(
                        protectoraId, TipoPublicacion.ADOPCION))
                .valoracionMedia(mediaDe(media == null ? Double.NaN : media))
                .totalValoraciones(valoracionRepository.countByProtectoraId(protectoraId))
                .build();
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /**
     * La protectora del usuario autenticado, exigiendo que esté ACTIVA.
     *
     * Los tres rechazos son 403 y llevan código propio porque el cliente tiene
     * que decir cosas distintas: que la cuenta no tiene protectora (no debería
     * pasar, pero de no comprobarlo saldría un 500 por un Optional vacío), que
     * está pendiente de verificación, o que fue rechazada.
     *
     * ES UN SWITCH EXHAUSTIVO Y SIN RAMA POR DEFECTO, A PROPÓSITO. Escrito como
     * dos "if" que rechazan PENDIENTE y RECHAZADA, lo que quedaba era una lista
     * negra: cualquier estado que no estuviera en ella concedía el acceso. Hoy
     * daría igual, porque el enum tiene exactamente tres valores, pero el día que
     * RF-11 necesite uno intermedio —"en revisión de documentación",
     * "suspendida"— ese estado nuevo abriría el panel entero salvo que quien lo
     * añada se acuerde de tocar también este método, sin que nada se lo exija.
     *
     * Así al revés: el acceso solo se concede en la rama ACTIVA, y añadir un
     * valor al enum ROMPE LA COMPILACIÓN aquí en lugar de colarse en ejecución.
     * Es la misma disciplina que MatchService.resolverAutor aplica sobre
     * TipoPublicacion, y por el mismo motivo.
     *
     * SOBRE LOS CÓDIGOS. PROTECTORA_PENDIENTE y PROTECTORA_RECHAZADA son
     * literalmente los mismos, mensaje incluido, que usa
     * AuthService.verificarCuentaOperativa al impedir el login: el cliente ya
     * sabe traducirlos y no conviene tener dos vocabularios para un mismo estado.
     *
     * SIN_PROTECTORA es el único que NO existe en el login, y no es un descuido.
     * Allí una cuenta con rol PROTECTORA sin ficha se trata como PENDIENTE, lo
     * que le dice al usuario que espere a un administrador cuando lo que hay es
     * una fila que falta y ninguna verificación va a arreglar. Aquí se llama por
     * su nombre, igual que en AnimalAdopcionService.protectoraDe, que es el
     * precedente del proyecto para este mismo caso.
     *
     * @throws CuentaNoAutorizadaException siempre que la protectora no esté ACTIVA (403)
     */
    private Protectora exigirProtectoraActiva(Long usuarioId) {
        Protectora protectora = protectoraRepository.findByUsuarioId(usuarioId)
                .orElseThrow(() -> new CuentaNoAutorizadaException("SIN_PROTECTORA",
                        "Tu cuenta no tiene una protectora asociada"));

        return switch (protectora.getEstado()) {
            case ACTIVA -> protectora;

            case PENDIENTE -> {
                log.warn("La protectora {} ha intentado abrir el panel sin estar verificada",
                        protectora.getId());
                throw new CuentaNoAutorizadaException("PROTECTORA_PENDIENTE",
                        "Tu protectora está pendiente de verificación por un administrador");
            }

            case RECHAZADA -> {
                log.warn("La protectora {}, rechazada, ha intentado abrir el panel",
                        protectora.getId());
                throw new CuentaNoAutorizadaException("PROTECTORA_RECHAZADA",
                        "La solicitud de tu protectora ha sido rechazada");
            }
        };
    }

    /**
     * Redondea una media a los dos decimales que expone la API, o devuelve null
     * si no hay media que redondear.
     *
     * Dos decimales por lo mismo que PROTECTORA.valoracion_media es un
     * NUMERIC(3,2): es la precisión con la que se ha decidido hablar de
     * estrellas. Y null en vez de cero cuando no hay valoraciones, porque un
     * cero se leería como la peor nota posible en lugar de como "todavía nada".
     */
    private static BigDecimal mediaDe(double media) {
        if (Double.isNaN(media)) {
            return null;
        }
        return BigDecimal.valueOf(media).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * El animal puede faltar si la fila desapareció entre las dos consultas, que
     * hoy no puede ocurrir —retirar ya no borra— pero tampoco merece un 500: la
     * solicitud se devuelve con los datos del animal vacíos en lugar de reventar
     * el listado entero por una fila.
     */
    private static SolicitudAdopcionResponse aSolicitudResponse(MatchRegistro solicitud,
                                                                AnimalAdopcion animal,
                                                                Long chatId) {
        return SolicitudAdopcionResponse.builder()
                .id(solicitud.getId())
                .interesado(aAutorResponse(solicitud.getUsuario()))
                .animalId(solicitud.getPublicacionId())
                .animalNombre(animal == null ? null : animal.getNombre())
                .animalEstado(animal == null ? null : animal.getEstado().name())
                .chatId(chatId)
                .createdAt(solicitud.getCreatedAt())
                .build();
    }

    private static ValoracionResponse aValoracionResponse(Valoracion valoracion) {
        return ValoracionResponse.builder()
                .id(valoracion.getId())
                .autor(aAutorResponse(valoracion.getUsuario()))
                .puntuacion(valoracion.getPuntuacion())
                .createdAt(valoracion.getCreatedAt())
                .build();
    }

    /** Datos públicos de un usuario: nunca su email ni su teléfono (RNF-10). */
    private static AutorResponse aAutorResponse(Usuario usuario) {
        return AutorResponse.builder()
                .id(usuario.getId())
                .nombre(usuario.getNombre())
                .fotoUrl(usuario.getFotoUrl())
                .build();
    }
}
