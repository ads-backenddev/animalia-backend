package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.ChatDetalleResponse;
import com.rescateanimal.backend.dto.response.ChatResumenResponse;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.ChatAjenoException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Mensaje;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.MensajeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lógica del chat: quién participa en cada conversación, listado, cabecera e
 * histórico de mensajes (RF-12).
 *
 * LOS DOS PARTICIPANTES. Un chat cuelga 1:1 de un match, y sus dos extremos son
 * los dos lados de ese match: quien deslizó (MATCH_REGISTRO.usuario) y quien
 * publicó lo deslizado. Ese segundo extremo depende del tipo de publicación:
 * en un avistamiento es su autor (AVISTAMIENTO.usuario) y en una adopción es la
 * cuenta de la protectora dueña del animal (ANIMAL_ADOPCION.protectora.usuario).
 *
 * En el caso del avistamiento no interviene el dueño de la alerta como tal:
 * normalmente ES quien desliza —el feed solo le esconde sus propios
 * avistamientos, no los que otros registran sobre su alerta—, y tomarlo a él
 * como segundo extremo abriría conversaciones de un usuario consigo mismo. Que
 * los dos extremos nunca coincidan lo garantiza MatchService, que rechaza con
 * 403 el deslizamiento sobre una publicación propia, sea del tipo que sea.
 *
 * El control de acceso de todo el bloque —REST y WebSocket— pasa por
 * exigirParticipante y esParticipante: son el único sitio donde se decide quién
 * entra en un chat.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /**
     * Tope de mensajes por página. El histórico se lee mientras se sube por la
     * conversación, así que pedir miles de una vez no sirve a ningún cliente y
     * sí permitiría forzar respuestas enormes (RNF-02).
     */
    static final int MAXIMO_MENSAJES_POR_PAGINA = 100;

    private final ChatRepository chatRepository;
    private final MensajeRepository mensajeRepository;
    private final AvistamientoRepository avistamientoRepository;
    private final AnimalAdopcionRepository animalAdopcionRepository;

    /**
     * La publicación que originó un chat, en las dos formas que puede tener.
     *
     * Es una interfaz sellada y no dos campos nulables porque "o es un
     * avistamiento o es un animal, nunca las dos cosas ni ninguna" deja así de
     * ser una convención que hay que recordar y pasa a comprobarlo el
     * compilador. Cada rama sabe además quién es su autor, que es lo único que
     * el control de acceso necesita saber de ella.
     */
    public sealed interface PublicacionDelChat {

        /** El participante que publicó: el otro extremo de la conversación. */
        Usuario autor();

        record DeAvistamiento(Avistamiento avistamiento) implements PublicacionDelChat {
            @Override
            public Usuario autor() {
                return avistamiento.getUsuario();
            }
        }

        record DeAdopcion(AnimalAdopcion animal) implements PublicacionDelChat {
            /** La cuenta de la protectora, no la organización: el chat es entre usuarios. */
            @Override
            public Usuario autor() {
                return animal.getProtectora().getUsuario();
            }
        }
    }

    /**
     * Un chat con sus dos extremos y la publicación que lo originó ya resueltos.
     *
     * Vive aquí y no en dto/ porque no sale nunca por la API: es el resultado de
     * la comprobación de permisos, y lo consumen MensajeService y el
     * interceptor de suscripciones del WebSocket.
     */
    public record Participantes(Chat chat, PublicacionDelChat publicacion, Usuario emisorDelMatch) {

        /** Quien publicó lo deslizado, sea el autor del avistamiento o la protectora. */
        public Usuario autorDeLaPublicacion() {
            return publicacion.autor();
        }

        public boolean incluye(Long usuarioId) {
            return emisorDelMatch.getId().equals(usuarioId)
                    || autorDeLaPublicacion().getId().equals(usuarioId);
        }

        /** El interlocutor, visto desde uno de los dos participantes. */
        public Usuario otro(Long usuarioId) {
            return emisorDelMatch.getId().equals(usuarioId)
                    ? autorDeLaPublicacion()
                    : emisorDelMatch;
        }

        /** El propio participante, ya cargado, para no volver a consultarlo. */
        public Usuario yo(Long usuarioId) {
            return emisorDelMatch.getId().equals(usuarioId)
                    ? emisorDelMatch
                    : autorDeLaPublicacion();
        }
    }

    // ---------------------------------------------------------------------
    // Control de acceso
    // ---------------------------------------------------------------------

    /**
     * Resuelve un chat y exige que el usuario sea uno de sus dos participantes.
     *
     * @throws ResourceNotFoundException si el chat no existe (404)
     * @throws ChatAjenoException        si existe pero el usuario no participa (403)
     */
    @Transactional(readOnly = true)
    public Participantes exigirParticipante(Long chatId, Long usuarioId) {
        Chat chat = chatRepository.buscarConMatch(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("El chat " + chatId + " no existe"));

        Participantes participantes = resolver(chat);

        if (!participantes.incluye(usuarioId)) {
            // Sin el id del intruso no habría forma de investigar un intento de
            // acceso; el contenido del chat no se registra (RNF-10).
            log.warn("El usuario {} ha intentado acceder al chat {}, del que no es participante",
                    usuarioId, chatId);
            throw new ChatAjenoException("No participas en esta conversación");
        }
        return participantes;
    }

    /**
     * Versión sin excepciones de la comprobación anterior, para el interceptor
     * que autoriza las suscripciones a /topic/chat/{chatId}: allí un chat
     * inexistente y uno ajeno se rechazan igual, y no hay respuesta HTTP que
     * distinguirlos.
     */
    @Transactional(readOnly = true)
    public boolean esParticipante(Long chatId, Long usuarioId) {
        return chatRepository.buscarConMatch(chatId)
                .map(chat -> resolver(chat).incluye(usuarioId))
                .orElse(false);
    }

    // ---------------------------------------------------------------------
    // Consultas (RF-12)
    // ---------------------------------------------------------------------

    /**
     * Conversaciones del usuario, con el interlocutor, el último mensaje y los
     * no leídos.
     *
     * Cuesta cinco consultas pase lo que pase, no cinco por conversación: una
     * para los chats, dos para las publicaciones de todos ellos —una por tabla,
     * y solo si hay chats de ese tipo—, otra para los últimos mensajes y otra
     * para los recuentos. Ordena por actividad —el último mensaje primero, y los
     * que aún no tienen ninguno por su fecha de apertura—, que es como se espera
     * una lista de chats; el orden se resuelve en memoria porque el usuario
     * tiene un puñado de conversaciones, no miles.
     */
    @Transactional(readOnly = true)
    public List<ChatResumenResponse> listarMisChats(Long usuarioId) {
        List<Chat> chats = chatRepository.buscarPorParticipante(
                usuarioId, TipoPublicacion.AVISTAMIENTO, TipoPublicacion.ADOPCION);
        if (chats.isEmpty()) {
            return List.of();
        }

        Map<ClavePublicacion, PublicacionDelChat> publicaciones = publicacionesDe(chats);
        List<Long> chatIds = chats.stream().map(Chat::getId).toList();

        Map<Long, Mensaje> ultimos = mensajeRepository.buscarUltimoDeCadaChat(chatIds).stream()
                .collect(Collectors.toMap(mensaje -> mensaje.getChat().getId(), Function.identity()));
        Map<Long, Long> noLeidos = mensajeRepository.contarNoLeidosPorChat(chatIds, usuarioId).stream()
                .collect(Collectors.toMap(MensajeRepository.ConteoNoLeidos::getChatId,
                        MensajeRepository.ConteoNoLeidos::getTotal));

        return chats.stream()
                // Un chat cuya publicación ya no existe se omite del listado en
                // vez de tumbarlo entero: en el detalle sí es un 404, porque
                // allí ese chat es lo que se ha pedido.
                //
                // Retirar un animal del catálogo ya no llega aquí: desde que es
                // un estado (RETIRADO) y no un DELETE, la fila sigue estando y
                // la conversación se mantiene, que es justo lo que se buscaba.
                // El filtro se queda porque nada en el esquema garantiza que no
                // vuelva a pasar —publicacion_id no tiene clave ajena—, y sin él
                // una fila borrada a mano tumbaría el listado entero de alguien.
                .filter(chat -> publicaciones.containsKey(ClavePublicacion.de(chat)))
                .map(chat -> {
                    Participantes participantes = new Participantes(chat,
                            publicaciones.get(ClavePublicacion.de(chat)),
                            chat.getMatch().getUsuario());
                    Mensaje ultimo = ultimos.get(chat.getId());

                    return ChatResumenResponse.builder()
                            .id(chat.getId())
                            .otroParticipante(aAutor(participantes.otro(usuarioId)))
                            .ultimoMensaje(ultimo == null ? null : aMensajeResponse(ultimo))
                            .noLeidos(noLeidos.getOrDefault(chat.getId(), 0L))
                            .createdAt(chat.getCreatedAt())
                            .build();
                })
                .sorted(Comparator.comparing(ChatService::ultimaActividad)
                        .thenComparing(ChatResumenResponse::getId)
                        .reversed())
                .toList();
    }

    /**
     * Cabecera de una conversación: con quién se habla y a raíz de qué (RF-12).
     *
     * El contexto que se devuelve depende de qué se deslizó: el avistamiento y
     * su alerta, o el animal y su protectora. Los campos del otro tipo se dejan
     * sin rellenar y el DTO los omite del JSON.
     */
    @Transactional(readOnly = true)
    public ChatDetalleResponse detalle(Long chatId, Long usuarioId) {
        Participantes participantes = exigirParticipante(chatId, usuarioId);
        Chat chat = participantes.chat();

        ChatDetalleResponse.ChatDetalleResponseBuilder detalle = ChatDetalleResponse.builder()
                .id(chat.getId())
                .otroParticipante(aAutor(participantes.otro(usuarioId)))
                .matchId(chat.getMatch().getId())
                .tipoPublicacion(chat.getMatch().getTipoPublicacion())
                .createdAt(chat.getCreatedAt());

        if (participantes.publicacion() instanceof PublicacionDelChat.DeAvistamiento origen) {
            Avistamiento avistamiento = origen.avistamiento();
            Alerta alerta = avistamiento.getAlerta();

            detalle.avistamientoId(avistamiento.getId())
                    .avistamientoFotoUrl(avistamiento.getFotoUrl())
                    .avistamientoDescripcion(avistamiento.getDescripcion())
                    .alertaId(alerta.getId())
                    .alertaEspecie(alerta.getEspecie())
                    .alertaFotoUrl(fotoDeLaAlertaVisible(alerta))
                    .alertaEstado(alerta.getEstado().name());

        } else if (participantes.publicacion() instanceof PublicacionDelChat.DeAdopcion origen) {
            AnimalAdopcion animal = origen.animal();

            detalle.animalId(animal.getId())
                    .animalNombre(animal.getNombre())
                    .animalEspecie(animal.getEspecie())
                    .animalFotoUrl(animal.getFotoUrl())
                    .animalEstado(animal.getEstado().name())
                    .protectoraId(animal.getProtectora().getId())
                    .protectoraNombre(animal.getProtectora().getNombreOrganizacion());
        }

        return detalle.build();
    }

    /**
     * La foto de la alerta, solo si la alerta sigue publicada.
     *
     * El chat sobrevive a la alerta —la conversación es también de la otra
     * parte—, pero no debe seguir enseñando lo que su dueño retiró o el
     * administrador moderó: sería la misma puerta trasera que el detalle de
     * GET /api/alertas/{id} cierra. Se queda lo que dice de qué alerta se
     * trataba y en qué quedó: id, especie y estado.
     */
    private static String fotoDeLaAlertaVisible(Alerta alerta) {
        return switch (alerta.getEstado()) {
            case ACTIVA, ENCONTRADA -> alerta.getFotoUrl();
            case RETIRADA, ELIMINADA -> null;
        };
    }

    /**
     * Histórico paginado del chat, del más reciente al más antiguo (RF-12).
     *
     * El orden lo fija el servidor y no el parámetro sort: una conversación solo
     * tiene una lectura útil —hacia atrás desde el último mensaje— y dejarlo
     * abierto permitiría paginar por cualquier columna de la entidad. El
     * desempate por id hace estable la paginación: sin él, dos mensajes con la
     * misma marca de tiempo podrían repetirse o perderse entre página y página.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<MensajeResponse> historico(Long chatId, Long usuarioId, Pageable paginacion) {
        exigirParticipante(chatId, usuarioId);

        PageRequest peticion = PageRequest.of(
                Math.max(0, paginacion.getPageNumber()),
                Math.min(Math.max(1, paginacion.getPageSize()), MAXIMO_MENSAJES_POR_PAGINA),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<Mensaje> pagina = mensajeRepository.buscarPorChat(chatId, peticion);
        return PaginaResponse.de(pagina, ChatService::aMensajeResponse);
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /**
     * Completa un chat con la publicación deslizada y sus dos participantes.
     *
     * La publicación se busca por MATCH_REGISTRO.publicacion_id, que es una
     * referencia polimórfica sin clave ajena: nada en la base de datos garantiza
     * que siga existiendo —una protectora puede retirar un animal del catálogo
     * en cualquier momento—, de ahí que su ausencia se trate como un 404 del
     * chat y no como un fallo interno.
     *
     * El tipo del match decide en qué tabla se busca. Leer el id sin mirarlo
     * devolvería la fila equivocada, porque el mismo número existe como
     * avistamiento y como animal.
     */
    private Participantes resolver(Chat chat) {
        MatchRegistro match = chat.getMatch();

        PublicacionDelChat publicacion = switch (match.getTipoPublicacion()) {
            case AVISTAMIENTO -> avistamientoRepository
                    .buscarConAutorYAlerta(List.of(match.getPublicacionId())).stream()
                    .findFirst()
                    .map(PublicacionDelChat.DeAvistamiento::new)
                    .map(PublicacionDelChat.class::cast)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "El avistamiento del chat " + chat.getId() + " ya no existe"));

            case ADOPCION -> animalAdopcionRepository
                    .buscarConProtectora(match.getPublicacionId())
                    .map(PublicacionDelChat.DeAdopcion::new)
                    .map(PublicacionDelChat.class::cast)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "El animal del chat " + chat.getId() + " ya no existe"));
        };

        return new Participantes(chat, publicacion, match.getUsuario());
    }

    /**
     * Publicaciones de un lote de chats, indexadas por tipo e id.
     *
     * Cuesta dos consultas —una por tabla— y no dos por chat: los ids se agrupan
     * antes por tipo y cada grupo se resuelve de una vez. Si el usuario no tiene
     * chats de un tipo, esa consulta ni se lanza.
     */
    private Map<ClavePublicacion, PublicacionDelChat> publicacionesDe(List<Chat> chats) {
        Map<TipoPublicacion, List<Long>> idsPorTipo = chats.stream()
                .collect(Collectors.groupingBy(chat -> chat.getMatch().getTipoPublicacion(),
                        Collectors.mapping(chat -> chat.getMatch().getPublicacionId(),
                                Collectors.toList())));

        Map<ClavePublicacion, PublicacionDelChat> publicaciones = new HashMap<>();

        List<Long> avistamientos = idsPorTipo.get(TipoPublicacion.AVISTAMIENTO);
        if (avistamientos != null) {
            for (Avistamiento avistamiento : avistamientoRepository
                    .buscarConAutorYAlerta(avistamientos.stream().distinct().toList())) {
                publicaciones.put(
                        new ClavePublicacion(TipoPublicacion.AVISTAMIENTO, avistamiento.getId()),
                        new PublicacionDelChat.DeAvistamiento(avistamiento));
            }
        }

        List<Long> animales = idsPorTipo.get(TipoPublicacion.ADOPCION);
        if (animales != null) {
            for (AnimalAdopcion animal : animalAdopcionRepository
                    .buscarConProtectoraPorIds(animales.stream().distinct().toList())) {
                publicaciones.put(
                        new ClavePublicacion(TipoPublicacion.ADOPCION, animal.getId()),
                        new PublicacionDelChat.DeAdopcion(animal));
            }
        }
        return publicaciones;
    }

    /**
     * Identifica una publicación de verdad: el id solo no basta, porque el mismo
     * número existe en las dos tablas y confundirlos mezclaría conversaciones.
     */
    private record ClavePublicacion(TipoPublicacion tipo, Long id) {

        static ClavePublicacion de(Chat chat) {
            return new ClavePublicacion(chat.getMatch().getTipoPublicacion(),
                    chat.getMatch().getPublicacionId());
        }
    }

    /** Momento de la última actividad: el último mensaje o, sin ninguno, la apertura. */
    private static Instant ultimaActividad(ChatResumenResponse chat) {
        return chat.getUltimoMensaje() == null
                ? chat.getCreatedAt()
                : chat.getUltimoMensaje().getCreatedAt();
    }

    static MensajeResponse aMensajeResponse(Mensaje mensaje) {
        return MensajeResponse.builder()
                .id(mensaje.getId())
                .chatId(mensaje.getChat().getId())
                .emisor(aAutor(mensaje.getEmisor()))
                .contenido(mensaje.getContenido())
                .leido(mensaje.getLeido())
                .createdAt(mensaje.getCreatedAt())
                .build();
    }

    /** Sin email ni teléfono: son datos personales y no viajan en el chat (RNF-10). */
    static AutorResponse aAutor(Usuario usuario) {
        return AutorResponse.builder()
                .id(usuario.getId())
                .nombre(usuario.getNombre())
                .fotoUrl(usuario.getFotoUrl())
                .build();
    }
}
