package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.ChatDetalleResponse;
import com.rescateanimal.backend.dto.response.ChatResumenResponse;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.ChatAjenoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Mensaje;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
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

import java.time.LocalDateTime;
import java.util.Comparator;
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
 * publicó lo deslizado (AVISTAMIENTO.usuario). No interviene el dueño de la
 * alerta como tal: normalmente ES quien desliza —el feed solo le esconde sus
 * propios avistamientos, no los que otros registran sobre su alerta—, y tomarlo
 * a él como segundo extremo abriría conversaciones de un usuario consigo mismo.
 * Que los dos extremos nunca coincidan lo garantiza MatchService, que rechaza
 * con 403 el deslizamiento sobre una publicación propia.
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

    /**
     * Un chat con sus dos extremos y la publicación que lo originó ya resueltos.
     *
     * Vive aquí y no en dto/ porque no sale nunca por la API: es el resultado de
     * la comprobación de permisos, y lo consumen MensajeService y el
     * interceptor de suscripciones del WebSocket.
     */
    public record Participantes(Chat chat, Avistamiento avistamiento,
                                Usuario emisorDelMatch, Usuario autorDelAvistamiento) {

        public boolean incluye(Long usuarioId) {
            return emisorDelMatch.getId().equals(usuarioId)
                    || autorDelAvistamiento.getId().equals(usuarioId);
        }

        /** El interlocutor, visto desde uno de los dos participantes. */
        public Usuario otro(Long usuarioId) {
            return emisorDelMatch.getId().equals(usuarioId) ? autorDelAvistamiento : emisorDelMatch;
        }

        /** El propio participante, ya cargado, para no volver a consultarlo. */
        public Usuario yo(Long usuarioId) {
            return emisorDelMatch.getId().equals(usuarioId) ? emisorDelMatch : autorDelAvistamiento;
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
     * Cuesta cuatro consultas pase lo que pase, no cuatro por conversación: una
     * para los chats, otra para los avistamientos de todos ellos, otra para los
     * últimos mensajes y otra para los recuentos. Ordena por actividad —el
     * último mensaje primero, y los que aún no tienen ninguno por su fecha de
     * apertura—, que es como se espera una lista de chats; el orden se resuelve
     * en memoria porque el usuario tiene un puñado de conversaciones, no miles.
     */
    @Transactional(readOnly = true)
    public List<ChatResumenResponse> listarMisChats(Long usuarioId) {
        List<Chat> chats = chatRepository.buscarPorParticipante(usuarioId, TipoPublicacion.AVISTAMIENTO);
        if (chats.isEmpty()) {
            return List.of();
        }

        Map<Long, Avistamiento> avistamientos = avistamientosDe(chats);
        List<Long> chatIds = chats.stream().map(Chat::getId).toList();

        Map<Long, Mensaje> ultimos = mensajeRepository.buscarUltimoDeCadaChat(chatIds).stream()
                .collect(Collectors.toMap(mensaje -> mensaje.getChat().getId(), Function.identity()));
        Map<Long, Long> noLeidos = mensajeRepository.contarNoLeidosPorChat(chatIds, usuarioId).stream()
                .collect(Collectors.toMap(MensajeRepository.ConteoNoLeidos::getChatId,
                        MensajeRepository.ConteoNoLeidos::getTotal));

        return chats.stream()
                // Un chat cuyo avistamiento ya no existe se omite del listado en
                // vez de tumbarlo entero: en el detalle sí es un 404, porque
                // allí ese chat es lo que se ha pedido.
                .filter(chat -> avistamientos.containsKey(chat.getMatch().getPublicacionId()))
                .map(chat -> {
                    Avistamiento avistamiento = avistamientos.get(chat.getMatch().getPublicacionId());
                    Participantes participantes = participantesDe(chat, avistamiento);
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

    /** Cabecera de una conversación: con quién se habla y a raíz de qué (RF-12). */
    @Transactional(readOnly = true)
    public ChatDetalleResponse detalle(Long chatId, Long usuarioId) {
        Participantes participantes = exigirParticipante(chatId, usuarioId);
        Chat chat = participantes.chat();
        Avistamiento avistamiento = participantes.avistamiento();
        Alerta alerta = avistamiento.getAlerta();

        return ChatDetalleResponse.builder()
                .id(chat.getId())
                .otroParticipante(aAutor(participantes.otro(usuarioId)))
                .matchId(chat.getMatch().getId())
                .avistamientoId(avistamiento.getId())
                .avistamientoFotoUrl(avistamiento.getFotoUrl())
                .avistamientoDescripcion(avistamiento.getDescripcion())
                .alertaId(alerta.getId())
                .alertaEspecie(alerta.getEspecie())
                .alertaFotoUrl(alerta.getFotoUrl())
                .alertaEstado(alerta.getEstado().name())
                .createdAt(chat.getCreatedAt())
                .build();
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
     * El avistamiento se busca por MATCH_REGISTRO.publicacion_id, que es una
     * referencia polimórfica sin clave ajena: nada en la base de datos garantiza
     * que siga existiendo, de ahí que su ausencia se trate como un 404 del chat
     * y no como un fallo interno.
     */
    private Participantes resolver(Chat chat) {
        MatchRegistro match = chat.getMatch();
        exigirTipoSoportado(match);

        Avistamiento avistamiento = avistamientoRepository
                .buscarConAutorYAlerta(List.of(match.getPublicacionId())).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El avistamiento del chat " + chat.getId() + " ya no existe"));

        return participantesDe(chat, avistamiento);
    }

    private Participantes participantesDe(Chat chat, Avistamiento avistamiento) {
        return new Participantes(chat, avistamiento,
                chat.getMatch().getUsuario(), avistamiento.getUsuario());
    }

    /** Avistamientos de un lote de chats, indexados por id, en una sola consulta. */
    private Map<Long, Avistamiento> avistamientosDe(List<Chat> chats) {
        List<Long> publicaciones = chats.stream()
                .peek(chat -> exigirTipoSoportado(chat.getMatch()))
                .map(chat -> chat.getMatch().getPublicacionId())
                .distinct()
                .toList();

        return avistamientoRepository.buscarConAutorYAlerta(publicaciones).stream()
                .collect(Collectors.toMap(Avistamiento::getId, Function.identity()));
    }

    /**
     * Hoy solo los avistamientos abren chat: MatchService rechaza el resto. La
     * comprobación existe porque publicacion_id apunta a tablas distintas según
     * el tipo, y leerlo como avistamiento sin mirar el tipo devolvería la fila
     * equivocada el día que las adopciones empiecen a generar match (RF-09).
     */
    private void exigirTipoSoportado(MatchRegistro match) {
        if (match.getTipoPublicacion() != TipoPublicacion.AVISTAMIENTO) {
            throw new OperacionNoPermitidaException("TIPO_PUBLICACION_NO_DISPONIBLE",
                    "Los chats de adopciones todavía no están disponibles");
        }
    }

    /** Momento de la última actividad: el último mensaje o, sin ninguno, la apertura. */
    private static LocalDateTime ultimaActividad(ChatResumenResponse chat) {
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
