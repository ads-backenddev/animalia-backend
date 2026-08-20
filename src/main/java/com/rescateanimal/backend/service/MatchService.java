package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearMatchRequest;
import com.rescateanimal.backend.dto.response.LimiteMatchResponse;
import com.rescateanimal.backend.dto.response.MatchResponse;
import com.rescateanimal.backend.exception.LimiteMatchesAlcanzadoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.PublicacionPropiaException;
import com.rescateanimal.backend.exception.PublicacionYaDeslizadaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.LimiteMatchDiario;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lógica de los deslizamientos sobre publicaciones: match y descarte, control
 * del límite diario y apertura del chat (RF-08, RF-12, RF-15, RF-24).
 */
@Slf4j
@Service
public class MatchService {

    private final MatchRegistroRepository matchRegistroRepository;
    private final LimiteMatchDiarioRepository limiteMatchDiarioRepository;
    private final ChatRepository chatRepository;
    private final AvistamientoRepository avistamientoRepository;
    private final AnimalAdopcionRepository animalAdopcionRepository;
    private final UsuarioRepository usuarioRepository;
    private final NotificacionService notificacionService;

    private final int limiteDiario;

    public MatchService(MatchRegistroRepository matchRegistroRepository,
                        LimiteMatchDiarioRepository limiteMatchDiarioRepository,
                        ChatRepository chatRepository,
                        AvistamientoRepository avistamientoRepository,
                        AnimalAdopcionRepository animalAdopcionRepository,
                        UsuarioRepository usuarioRepository,
                        NotificacionService notificacionService,
                        @Value("${app.matches.limite-diario}") int limiteDiario) {
        this.matchRegistroRepository = matchRegistroRepository;
        this.limiteMatchDiarioRepository = limiteMatchDiarioRepository;
        this.chatRepository = chatRepository;
        this.avistamientoRepository = avistamientoRepository;
        this.animalAdopcionRepository = animalAdopcionRepository;
        this.usuarioRepository = usuarioRepository;
        this.notificacionService = notificacionService;
        this.limiteDiario = limiteDiario;
    }

    // ---------------------------------------------------------------------
    // Deslizamiento (RF-08, RF-15, RF-24)
    // ---------------------------------------------------------------------

    /**
     * Registra un deslizamiento sobre una publicación.
     *
     * El orden de las comprobaciones importa: primero se valida todo lo que
     * puede rechazar la petición y solo al final se consume cuota. Al revés, un
     * segundo swipe sobre la misma publicación —que acaba en 409— le habría
     * gastado al usuario un match del día.
     *
     * Todo lo que sigue al consumo de cuota ocurre en la misma transacción:
     * registro, chat y notificación son las tres partes de "hacer match" y no
     * tiene sentido que exista una sin las otras. Es distinto del caso de las
     * alertas, donde el aviso a la zona sí es un efecto colateral que no debe
     * poder tumbar la publicación.
     *
     * Tampoco contradice a MensajeService.enviar, que difunde DESPUÉS de
     * confirmar: aquí la notificación es una escritura en la base de datos y dos
     * escrituras sí pueden ser atómicas entre sí, mientras que una difusión por
     * WebSocket no lo es —ningún rollback la retira— y lo único elegible allí es
     * cuándo sale. El porqué completo está en el javadoc de aquel método.
     */
    @Transactional
    public MatchResponse deslizar(CrearMatchRequest peticion, Long usuarioId) {
        Usuario autorDeLaPublicacion = resolverAutor(peticion, usuarioId);

        if (matchRegistroRepository.existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                usuarioId, peticion.getPublicacionId(), peticion.getTipoPublicacion())) {
            throw new PublicacionYaDeslizadaException("Ya habías deslizado esta publicación");
        }

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("El usuario no existe"));

        boolean interesado = Boolean.TRUE.equals(peticion.getInteresado());
        if (interesado) {
            consumirCuotaDiaria(usuario);
        }

        MatchRegistro registro = new MatchRegistro();
        registro.setUsuario(usuario);
        registro.setPublicacionId(peticion.getPublicacionId());
        registro.setTipoPublicacion(peticion.getTipoPublicacion());
        registro.setInteresado(interesado);
        MatchRegistro guardado = matchRegistroRepository.save(registro);

        if (!interesado) {
            // Un descarte solo deja constancia para que el feed no lo repita:
            // ni chat, ni notificación, ni cuota (RF-15).
            log.debug("Usuario {} descartó la publicación {} de tipo {}",
                    usuarioId, peticion.getPublicacionId(), peticion.getTipoPublicacion());
            return aMatchResponse(guardado, null);
        }

        Chat chat = new Chat();
        chat.setMatch(guardado);
        Chat chatGuardado = chatRepository.save(chat);

        // Avisa a quien publicó lo deslizado de que alguien ha mostrado interés.
        // El destinatario es siempre el OTRO extremo del chat que se acaba de
        // abrir, nunca quien deslizó: en los avistamientos es su autor y no el
        // dueño de la alerta —que suele ser el propio deslizador—, y en las
        // adopciones es la cuenta de la protectora dueña del animal.
        notificacionService.notificarNuevoMatch(
                autorDeLaPublicacion, guardado.getId(), usuarioId);

        log.info("Match {} del usuario {} sobre la publicación {} de tipo {}; chat {}",
                guardado.getId(), usuarioId, peticion.getPublicacionId(),
                peticion.getTipoPublicacion(), chatGuardado.getId());

        return aMatchResponse(guardado, chatGuardado.getId());
    }

    /**
     * Carga la publicación deslizada y devuelve a quien la publicó, que es el
     * otro extremo del chat y el destinatario de la notificación.
     *
     * Es lo único que cambia entre tipos de publicación (RF-08 y RF-09): cada
     * uno vive en su tabla, tiene su propio dueño y su propia regla de cuándo
     * deja de admitir matches. Todo lo que viene después —swipe duplicado,
     * cuota diaria, registro, chat y notificación— es común y no se duplica.
     *
     * El orden dentro de cada rama importa y es el mismo en las dos: primero se
     * rechaza deslizar lo propio y solo después se mira el estado. Al revés, a
     * quien intentara deslizar su propia publicación ya cerrada se le diría que
     * está cerrada en vez de que es suya.
     */
    private Usuario resolverAutor(CrearMatchRequest peticion, Long usuarioId) {
        return switch (peticion.getTipoPublicacion()) {
            case AVISTAMIENTO -> autorDelAvistamiento(peticion.getPublicacionId(), usuarioId);
            case ADOPCION -> protectoraDelAnimal(peticion.getPublicacionId(), usuarioId);
        };
    }

    private Usuario autorDelAvistamiento(Long avistamientoId, Long usuarioId) {
        Avistamiento avistamiento = avistamientoRepository.findById(avistamientoId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El avistamiento " + avistamientoId + " no existe"));

        exigirQueNoSeaPropia(avistamiento.getUsuario(), usuarioId);

        if (avistamiento.getAlerta().getEstado() != EstadoAlerta.ACTIVA) {
            // El feed filtra por alerta ACTIVA, pero nada impide llamar al
            // endpoint con el id de un avistamiento cuya alerta se cerró después
            // de mostrarse la tarjeta. Sin esta comprobación se abriría un chat y
            // se avisaría al dueño por un animal que ya apareció, gastándole
            // además un match del día.
            throw new OperacionNoPermitidaException("ALERTA_NO_ACTIVA",
                    "La alerta de este avistamiento ya no está activa: el animal ya apareció");
        }
        return avistamiento.getUsuario();
    }

    /**
     * El otro extremo de un match de adopción es la CUENTA de la protectora
     * dueña del animal (PROTECTORA.usuario), no la protectora como organización:
     * el chat y las notificaciones se dirigen a usuarios, y la protectora no
     * tiene identidad propia en ninguno de los dos.
     */
    private Usuario protectoraDelAnimal(Long animalId, Long usuarioId) {
        AnimalAdopcion animal = animalAdopcionRepository.buscarConProtectora(animalId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El animal " + animalId + " no existe"));

        exigirQueNoSeaPropia(animal.getProtectora().getUsuario(), usuarioId);

        if (animal.getEstado() != EstadoAnimal.DISPONIBLE) {
            // Equivalente a la alerta ya cerrada: el animal ya no se ofrece, así
            // que un match nuevo abriría una conversación sobre algo cerrado y
            // le gastaría al ciudadano un match del día (RF-20).
            //
            // El motivo se distingue porque no significan lo mismo para quien lo
            // lee: ADOPTADO es un final feliz y RETIRADO puede ser cualquier
            // cosa. Decirle "ya ha sido adoptado" de un animal retirado sería
            // sencillamente falso.
            throw new OperacionNoPermitidaException("ANIMAL_NO_DISPONIBLE",
                    animal.getEstado() == EstadoAnimal.ADOPTADO
                            ? "Este animal ya ha sido adoptado"
                            : "Este animal ya no está en el catálogo");
        }
        return animal.getProtectora().getUsuario();
    }

    /**
     * Nadie desliza lo suyo, ni para hacer match ni para descartarlo. Sin esto,
     * un match sobre lo propio abriría un chat de un usuario consigo mismo; en
     * adopción, además, es lo que impide que una protectora se deslice a sus
     * propios animales. El feed ya los excluye: esto cubre la llamada directa.
     */
    private void exigirQueNoSeaPropia(Usuario autor, Long usuarioId) {
        if (autor.getId().equals(usuarioId)) {
            throw new PublicacionPropiaException("No puedes deslizar una publicación tuya");
        }
    }

    /**
     * Consume un match de la cuota del día o falla con 429 (RF-24).
     *
     * Son dos sentencias atómicas en vez de leer-decidir-guardar: con el
     * contador en memoria, dos peticiones simultáneas leerían el mismo valor y
     * el usuario acabaría con un match de más. El INSERT crea la fila del día si
     * es el primer match, y el UPDATE solo cuenta si aún queda cupo.
     */
    private void consumirCuotaDiaria(Usuario usuario) {
        LocalDate hoy = LocalDate.now();

        limiteMatchDiarioRepository.crearContadorSiNoExiste(usuario.getId(), hoy);

        if (limiteMatchDiarioRepository.incrementarSiHayCupo(usuario.getId(), hoy, limiteDiario) == 0) {
            log.info("Usuario {} ha agotado sus {} matches diarios", usuario.getId(), limiteDiario);
            throw new LimiteMatchesAlcanzadoException(limiteDiario, reinicioDelContador(hoy));
        }
    }

    // ---------------------------------------------------------------------
    // Consultas
    // ---------------------------------------------------------------------

    /**
     * Matches del usuario autenticado (RF-08). Solo los positivos: los descartes
     * comparten tabla pero no son matches.
     */
    @Transactional(readOnly = true)
    public List<MatchResponse> listarMisMatches(Long usuarioId) {
        List<MatchRegistro> matches =
                matchRegistroRepository.findByUsuarioIdAndInteresadoTrueOrderByCreatedAtDesc(usuarioId);

        if (matches.isEmpty()) {
            return List.of();
        }

        // Los chats se traen de una vez y se indexan por match, en lugar de
        // consultarlos uno a uno dentro del bucle (N+1).
        Map<Long, Long> chatPorMatch = chatRepository
                .findByMatchIdIn(matches.stream().map(MatchRegistro::getId).toList())
                .stream()
                .collect(Collectors.toMap(chat -> chat.getMatch().getId(), Chat::getId));

        return matches.stream()
                .map(match -> aMatchResponse(match, chatPorMatch.get(match.getId())))
                .toList();
    }

    /** Estado del límite diario, para que el cliente lo muestre antes de agotarlo (RF-24). */
    @Transactional(readOnly = true)
    public LimiteMatchResponse consultarLimite(Long usuarioId) {
        LocalDate hoy = LocalDate.now();
        int consumidos = limiteMatchDiarioRepository.findByUsuarioIdAndFecha(usuarioId, hoy)
                .map(LimiteMatchDiario::getContador)
                .orElse(0);

        return LimiteMatchResponse.builder()
                .limiteDiario(limiteDiario)
                .consumidos(consumidos)
                .restantes(Math.max(0, limiteDiario - consumidos))
                .reiniciaEn(reinicioDelContador(hoy))
                .build();
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /**
     * Medianoche del día siguiente: el contador no se reinicia con una tarea
     * programada, simplemente al cambiar la fecha se empieza una fila nueva.
     *
     * Devuelve LocalDateTime y no Instant, a diferencia del resto de marcas de
     * tiempo de la API: es una fecha de calendario derivada de LocalDate, no un
     * instante. Ver la nota de LimiteMatchResponse.reiniciaEn.
     */
    private LocalDateTime reinicioDelContador(LocalDate hoy) {
        return hoy.plusDays(1).atStartOfDay();
    }

    private MatchResponse aMatchResponse(MatchRegistro registro, Long chatId) {
        return MatchResponse.builder()
                .id(registro.getId())
                .publicacionId(registro.getPublicacionId())
                .tipoPublicacion(registro.getTipoPublicacion())
                .interesado(registro.getInteresado())
                .chatId(chatId)
                .createdAt(registro.getCreatedAt())
                .build();
    }
}
