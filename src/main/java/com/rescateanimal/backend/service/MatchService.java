package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearMatchRequest;
import com.rescateanimal.backend.dto.response.LimiteMatchResponse;
import com.rescateanimal.backend.dto.response.MatchResponse;
import com.rescateanimal.backend.exception.AlertaAjenaException;
import com.rescateanimal.backend.exception.LimiteMatchesAlcanzadoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.PublicacionPropiaException;
import com.rescateanimal.backend.exception.PublicacionYaDeslizadaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.LimiteMatchDiario;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AlertaRepository;
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
import java.time.ZoneId;
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

    /**
     * Zona con la que se decide qué día natural es "hoy" para la cuota diaria
     * (RF-24). Fijada a propósito y no heredada de la JVM; el porqué y sus
     * límites están en el javadoc de consumirCuotaDiaria.
     */
    private static final ZoneId ZONA_CUOTA = ZoneId.of("Europe/Madrid");

    private final MatchRegistroRepository matchRegistroRepository;
    private final LimiteMatchDiarioRepository limiteMatchDiarioRepository;
    private final ChatRepository chatRepository;
    private final AvistamientoRepository avistamientoRepository;
    private final AnimalAdopcionRepository animalAdopcionRepository;
    private final AlertaRepository alertaRepository;
    private final UsuarioRepository usuarioRepository;
    private final NotificacionService notificacionService;

    private final int limiteDiario;

    public MatchService(MatchRegistroRepository matchRegistroRepository,
                        LimiteMatchDiarioRepository limiteMatchDiarioRepository,
                        ChatRepository chatRepository,
                        AvistamientoRepository avistamientoRepository,
                        AnimalAdopcionRepository animalAdopcionRepository,
                        AlertaRepository alertaRepository,
                        UsuarioRepository usuarioRepository,
                        NotificacionService notificacionService,
                        @Value("${app.matches.limite-diario}") int limiteDiario) {
        this.matchRegistroRepository = matchRegistroRepository;
        this.limiteMatchDiarioRepository = limiteMatchDiarioRepository;
        this.chatRepository = chatRepository;
        this.avistamientoRepository = avistamientoRepository;
        this.animalAdopcionRepository = animalAdopcionRepository;
        this.alertaRepository = alertaRepository;
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
     * LA CUOTA DIARIA (RF-24) ES SOLO DE ADOPCIÓN. El límite existe para que
     * nadie haga match en masa con publicaciones ajenas; sobre los avistamientos
     * ya no hay ese riesgo, porque solo los desliza el dueño de su propia
     * alerta, y un dueño con muchos avistamientos en un día no debe quedarse sin
     * poder revisarlos. Ver consumeCuota.
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
     *
     * ORDEN DE BLOQUEO, de principio a fin de la transacción:
     * 1. En un avistamiento, la fila de su ALERTA y después la del propio
     *    AVISTAMIENTO, las dos con FOR SHARE (autorDelAvistamiento). Antes no
     *    se toma ningún bloqueo: el 404 y los dos 403 se resuelven con
     *    lecturas simples.
     * 2. En un match de adopción, la fila del CONTADOR DIARIO del usuario, con
     *    el UPDATE condicional de consumirCuotaDiaria. Un avistamiento no la
     *    toca: no consume cuota.
     * 3. Los INSERT de MATCH_REGISTRO —la clave de su índice único—, de CHAT y
     *    de NOTIFICACION, que sobre sus filas padre solo toman el FOR KEY SHARE
     *    de comprobar la clave ajena.
     * Quién más toma esas filas, y por qué no hay ciclo:
     * - La ALERTA la bloquean en exclusiva editarla, retirarla, cerrarla y
     *   moderarla (FOR NO KEY UPDATE), y en compartido registrar un
     *   avistamiento, que después toma la fila de su cupo diario en esa alerta
     *   (LimiteAvistamientoDiario), una fila que el match no toca. Ninguna toca
     *   después un avistamiento ya registrado ni el contador. La moderación
     *   borra además la fila de FOTO de la alerta, que es lo último que toma;
     *   el match no toca FOTO.
     * - El AVISTAMIENTO solo lo escribe su moderación
     *   (AdminService.retirarAvistamiento): el UPDATE de su estado y nada más,
     *   ni la alerta, ni FOTO —su foto no se borra—, ni el contador.
     * - El CONTADOR solo lo toca un match de adopción, que no bloquea alertas
     *   ni avistamientos.
     * La única espera de una alerta hacia un avistamiento es la del propio
     * match, y nadie espera en sentido contrario: quien tiene un avistamiento
     * bloqueado no pide después su alerta. Sin un par de bloqueos pedidos en
     * orden contrario no hay ciclo posible.
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
        if (interesado && consumeCuota(peticion.getTipoPublicacion())) {
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
        // dueño de la alerta —que es siempre quien desliza—, y en las
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
     * registro, chat y notificación— es común y no se duplica; la cuota diaria
     * también, pero solo la consume la adopción (consumeCuota).
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

    /**
     * El otro extremo de un match de avistamiento es quien lo registró, y solo
     * puede deslizarlo el DUEÑO DE LA ALERTA (403 ALERTA_AJENA para cualquier
     * otro, en los dos sentidos del swipe). Él es quien reconoce a su animal en
     * la foto; un desconocido no tiene nada que reconocer ni motivo para abrir
     * un chat con el testigo. Se comprueba aquí y no solo en el feed porque el
     * feed no impide llamar a la API con cualquier id.
     *
     * ORDEN DE LAS COMPROBACIONES: 404 si el avistamiento no existe; 403
     * ALERTA_AJENA; 403 PUBLICACION_PROPIA si el dueño desliza un avistamiento
     * que registró él; 400 AVISTAMIENTO_ELIMINADO; 400 AUTOR_NO_DISPONIBLE; 400
     * ALERTA_NO_ACTIVA. El permiso va antes que el estado, y de los dos
     * permisos, el de la alerta primero: a un desconocido se le dice que la
     * alerta no es suya aunque el avistamiento sí lo sea. De los estados, va
     * primero lo propio de la tarjeta —el avistamiento y su autor— y después
     * la alerta de la que cuelga.
     *
     * LOS PERMISOS SE COMPRUEBAN SIN BLOQUEO, con tres escalares: de qué alerta
     * cuelga el avistamiento, quién lo registró y quién publicó la alerta.
     * Ninguno cambia nunca, así que comprobarlos sin bloqueo es seguro, y
     * bloquear antes dejaría a cualquier usuario frenar al dueño y al
     * administrador con un swipe condenado al 403. Mismo patrón que
     * AlertaService.exigirAlertaPropia.
     *
     * LOS ESTADOS SE LEEN CON BLOQUEO COMPARTIDO (FOR SHARE), primero el de la
     * alerta y después el del avistamiento, y solo cuando ya se sabe que quien
     * desliza es el dueño:
     * - El de la alerta cierra la carrera con su cierre y su moderación: sin
     *   él, un match podía confirmarse a la vez que el cierre, abrir un chat
     *   sobre una alerta ENCONTRADA y dejar al testigo sin el aviso de
     *   ALERTA_ENCONTRADA, porque sus destinatarios se calculan con los
     *   matches ya confirmados.
     * - El del avistamiento cierra la misma carrera con la moderación del
     *   avistamiento: sin él, un administrador que lo retirara entre la lectura
     *   del estado y el commit del match dejaba un chat abierto sobre contenido
     *   retirado.
     *
     * NINGUNA DE LAS DOS SE CARGA ANTES DE SU BLOQUEO. Por eso los permisos van
     * con escalares (buscarAlertaYAutor, buscarAutorId) y no navegando
     * entidades: una entidad cargada antes quedaría en el contexto de
     * persistencia, y la lectura bloqueada devolvería esa copia anterior al
     * bloqueo (ver AlertaRepository.buscarAutorId).
     *
     * LA CUENTA DEL AUTOR SE LEE SIN BLOQUEAR SU FILA, a diferencia de las otras
     * dos. Si el administrador la bloquea —o su dueño se da de baja— en el mismo
     * instante en que se hace el match, el chat puede abrirse igualmente, y el
     * resultado es el mismo que si el match hubiera llegado un momento antes:
     * un chat con una cuenta que ya no responde, como los que ya existían antes
     * del bloqueo. Cerrarlo pediría un tercer bloqueo en cada match.
     */
    private Usuario autorDelAvistamiento(Long avistamientoId, Long usuarioId) {
        AvistamientoRepository.AlertaYAutor ids = avistamientoRepository.buscarAlertaYAutor(avistamientoId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El avistamiento " + avistamientoId + " no existe"));
        Long alertaId = ids.getAlertaId();

        Long duenoId = alertaRepository.buscarAutorId(alertaId)
                .orElseThrow(() -> new ResourceNotFoundException("La alerta del avistamiento no existe"));
        if (!duenoId.equals(usuarioId)) {
            throw new AlertaAjenaException(
                    "Solo quien publicó la alerta puede hacer match con sus avistamientos");
        }

        exigirQueNoSeaPropia(ids.getAutorId(), usuarioId);

        // Los dos bloqueos, y en este orden: la alerta antes que el avistamiento
        // (ver ORDEN DE BLOQUEO en deslizar).
        Alerta alerta = alertaRepository.bloquearParaAvistar(alertaId)
                .orElseThrow(() -> new ResourceNotFoundException("La alerta del avistamiento no existe"));
        Avistamiento avistamiento = avistamientoRepository.bloquearParaDeslizar(avistamientoId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El avistamiento " + avistamientoId + " no existe"));

        if (avistamiento.getEstado() != EstadoAvistamiento.VISIBLE) {
            // Retirado por el administrador (RF-11). El feed ya lo excluye, pero
            // nada impide llamar al endpoint con el id de un avistamiento que se
            // retiró después de mostrarse la tarjeta. Sin esto se abriría un chat
            // sobre contenido que incumplía las normas.
            throw new OperacionNoPermitidaException("AVISTAMIENTO_ELIMINADO",
                    "Este avistamiento fue retirado por incumplir las normas");
        }
        Usuario autor = avistamiento.getUsuario();
        if (!Boolean.TRUE.equals(autor.getActivo())) {
            // Bloqueado por el administrador o dado de baja (RF-11, RF-19). El
            // feed ya no lo ofrece, pero la tarjeta pudo mostrarse antes, y el
            // match abriría un chat con alguien que no puede responder. El
            // mensaje no distingue los dos casos: que una cuenta esté bloqueada
            // no es asunto de quien desliza.
            throw new OperacionNoPermitidaException("AUTOR_NO_DISPONIBLE",
                    "Quien registró este avistamiento ya no está disponible");
        }
        if (alerta.getEstado() != EstadoAlerta.ACTIVA) {
            // El feed solo existe para una alerta ACTIVA, pero nada impide llamar
            // al endpoint con el id de un avistamiento cuya alerta se cerró
            // después de mostrarse la tarjeta. Sin esta comprobación se abriría
            // un chat con el testigo por un animal que ya apareció.
            throw new OperacionNoPermitidaException("ALERTA_NO_ACTIVA",
                    "La alerta de este avistamiento ya no está activa: el animal ya apareció");
        }
        return autor;
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

        exigirQueNoSeaPropia(animal.getProtectora().getUsuario().getId(), usuarioId);

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
    private void exigirQueNoSeaPropia(Long autorId, Long usuarioId) {
        if (autorId.equals(usuarioId)) {
            throw new PublicacionPropiaException("No puedes deslizar una publicación tuya");
        }
    }

    /**
     * Si un match de este tipo gasta cuota diaria (RF-24): solo los de adopción.
     *
     * Con un switch y no con una comparación para que un tipo de publicación
     * nuevo no compile hasta que alguien decida si consume cuota, en vez de
     * heredar en silencio una de las dos respuestas.
     */
    private static boolean consumeCuota(TipoPublicacion tipo) {
        return switch (tipo) {
            case ADOPCION -> true;
            case AVISTAMIENTO -> false;
        };
    }

    /**
     * Consume un match de adopción de la cuota del día o falla con 429 (RF-24).
     *
     * Son dos sentencias atómicas en vez de leer-decidir-guardar: con el
     * contador en memoria, dos peticiones simultáneas leerían el mismo valor y
     * el usuario acabaría con un match de más. El INSERT crea la fila del día si
     * es el primer match, y el UPDATE solo cuenta si aún queda cupo.
     *
     * "HOY" ES EL DÍA NATURAL EN EUROPE/MADRID, fijado explícitamente en
     * ZONA_CUOTA. El contador es de calendario y no de instante —la cuota se
     * cuenta por día natural, LIMITE_MATCH_DIARIO.fecha es un DATE, ver
     * LimiteMatchResponse.reiniciaEn—, así que no se arreglaba migrando el tipo
     * como se hizo con la ventana de RF-25: había que decidir de quién es el
     * día. Sin fijarlo, el día era el de la zona de la JVM y la cuota se
     * reiniciaba a una hora distinta en cada entorno: en Railway (UTC) a la
     * 01:00 o a las 02:00 de la madrugada peninsular según la época del año, y
     * en el portátil de desarrollo a medianoche.
     *
     * SIMPLIFICACIÓN CONSCIENTE, Y NO ES CORRECTA PARA CANARIAS. Vale para
     * España peninsular, que es el alcance geográfico del proyecto. Un usuario
     * en Canarias va una hora por detrás y ve su cuota reiniciarse a las 23:00
     * de su noche, no a medianoche. Resolverlo de verdad exige que el cliente
     * envíe su zona y guardarla junto al contador —o contar la cuota por
     * instante en vez de por día—, y eso es una decisión de producto de RF-24
     * que no se ha tomado. Queda anotada en docs/api-rest.md, §16.
     */
    private void consumirCuotaDiaria(Usuario usuario) {
        LocalDate hoy = LocalDate.now(ZONA_CUOTA);

        limiteMatchDiarioRepository.crearContadorSiNoExiste(usuario.getId(), hoy);

        if (limiteMatchDiarioRepository.incrementarSiHayCupo(usuario.getId(), hoy, limiteDiario) == 0) {
            log.info("Usuario {} ha agotado sus {} matches de adopción diarios",
                    usuario.getId(), limiteDiario);
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
        LocalDate hoy = LocalDate.now(ZONA_CUOTA);
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
     * Medianoche de ZONA_CUOTA, porque "hoy" ya viene decidido con esa zona.
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
