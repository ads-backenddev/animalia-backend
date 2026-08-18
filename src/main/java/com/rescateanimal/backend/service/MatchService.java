package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearMatchRequest;
import com.rescateanimal.backend.dto.response.LimiteMatchResponse;
import com.rescateanimal.backend.dto.response.MatchResponse;
import com.rescateanimal.backend.exception.LimiteMatchesAlcanzadoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.PublicacionPropiaException;
import com.rescateanimal.backend.exception.PublicacionYaDeslizadaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.LimiteMatchDiario;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
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
    private final UsuarioRepository usuarioRepository;
    private final NotificacionService notificacionService;

    private final int limiteDiario;

    public MatchService(MatchRegistroRepository matchRegistroRepository,
                        LimiteMatchDiarioRepository limiteMatchDiarioRepository,
                        ChatRepository chatRepository,
                        AvistamientoRepository avistamientoRepository,
                        UsuarioRepository usuarioRepository,
                        NotificacionService notificacionService,
                        @Value("${app.matches.limite-diario}") int limiteDiario) {
        this.matchRegistroRepository = matchRegistroRepository;
        this.limiteMatchDiarioRepository = limiteMatchDiarioRepository;
        this.chatRepository = chatRepository;
        this.avistamientoRepository = avistamientoRepository;
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
     */
    @Transactional
    public MatchResponse deslizar(CrearMatchRequest peticion, Long usuarioId) {
        if (peticion.getTipoPublicacion() != TipoPublicacion.AVISTAMIENTO) {
            throw new OperacionNoPermitidaException("TIPO_PUBLICACION_NO_DISPONIBLE",
                    "Por ahora solo se pueden deslizar avistamientos; las adopciones llegarán después");
        }

        Avistamiento avistamiento = avistamientoRepository.findById(peticion.getPublicacionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El avistamiento " + peticion.getPublicacionId() + " no existe"));

        if (avistamiento.getUsuario().getId().equals(usuarioId)) {
            // Sin esto, un match sobre lo propio abriría un chat de uno consigo
            // mismo. El feed ya los excluye; esto cubre la llamada directa.
            throw new PublicacionPropiaException("No puedes deslizar una publicación tuya");
        }

        if (avistamiento.getAlerta().getEstado() != EstadoAlerta.ACTIVA) {
            // Mismo motivo que el caso de arriba: el feed filtra por alerta
            // ACTIVA, pero nada impide llamar al endpoint con el id de un
            // avistamiento cuya alerta se cerró después de mostrarse la tarjeta.
            // Sin esta comprobación se abriría un chat y se avisaría al dueño por
            // un animal que ya apareció, gastándole además un match del día.
            throw new OperacionNoPermitidaException("ALERTA_NO_ACTIVA",
                    "La alerta de este avistamiento ya no está activa: el animal ya apareció");
        }

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
            log.debug("Usuario {} descartó el avistamiento {}", usuarioId, peticion.getPublicacionId());
            return aMatchResponse(guardado, null);
        }

        Chat chat = new Chat();
        chat.setMatch(guardado);
        Chat chatGuardado = chatRepository.save(chat);

        // Avisa a quien perdió al animal de que alguien reconoce su avistamiento.
        notificacionService.notificarNuevoMatch(
                avistamiento.getAlerta().getUsuario(), guardado.getId(), usuarioId);

        log.info("Match {} del usuario {} sobre el avistamiento {}; chat {}",
                guardado.getId(), usuarioId, peticion.getPublicacionId(), chatGuardado.getId());

        return aMatchResponse(guardado, chatGuardado.getId());
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
