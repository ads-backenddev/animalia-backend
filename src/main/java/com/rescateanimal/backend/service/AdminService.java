package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ProtectoraAdminResponse;
import com.rescateanimal.backend.dto.response.ReporteResponse;
import com.rescateanimal.backend.dto.response.UsuarioAdminResponse;
import com.rescateanimal.backend.exception.AutobloqueoException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Reporte;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.EstadoReporte;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.ReporteRepository;
import com.rescateanimal.backend.repository.UsuarioAdminSpec;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lógica de administración: bloqueo de usuarios, verificación de protectoras,
 * revisión de reportes y retirada de publicaciones (RF-11).
 *
 * EL ROL ADMIN ES TODO EL PERMISO QUE HACE FALTA, y por eso aquí no hay una
 * segunda comprobación como la de las otras dos áreas del proyecto. En el
 * catálogo, "ser protectora" no basta para tocar un animal concreto —hay que ser
 * la suya—; en el panel, no basta con el rol porque además hay que estar
 * verificada. Administrar es distinto: no hay un subconjunto de usuarios que
 * sean "los tuyos", el ámbito del administrador es la plataforma entera. Lo
 * único que se comprueba de más es que nadie se bloquee a sí mismo.
 *
 * NADA SE BORRA FÍSICAMENTE. Retirar una publicación la deja en un estado
 * terminal —ELIMINADA, ELIMINADO o RETIRADO según la tabla— y bloquear una
 * cuenta la deja inoperativa, pero ninguna fila desaparece. El motivo es el
 * mismo en los dos casos: MATCH_REGISTRO y REPORTE apuntan a las publicaciones
 * con referencias polimórficas sin clave ajena, y nueve claves ajenas apuntan a
 * USUARIO. Borrar de verdad dejaría huérfanos chats, mensajes y reportes sin que
 * la base de datos pudiera impedirlo. El borrado real de datos personales es
 * otra cosa —RF-19, bloque 10— y tiene sus propias reglas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    /** Tope de usuarios por página, por el mismo motivo que en el catálogo (RNF-02). */
    static final int MAXIMO_POR_PAGINA = 100;

    private final UsuarioRepository usuarioRepository;
    private final ProtectoraRepository protectoraRepository;
    private final ReporteRepository reporteRepository;
    private final AlertaRepository alertaRepository;
    private final AvistamientoRepository avistamientoRepository;
    private final AnimalAdopcionRepository animalAdopcionRepository;
    private final NotificacionService notificacionService;

    // ---------------------------------------------------------------------
    // Usuarios (RF-11)
    // ---------------------------------------------------------------------

    /**
     * Listado paginado de usuarios, con filtros opcionales por rol y por estado
     * de bloqueo.
     *
     * Los dos filtros son independientes y combinables; qué significa cada uno,
     * y por qué "activo" es un Boolean y no un boolean, lo describe
     * UsuarioAdminSpec.
     *
     * El orden es por alta descendente: lo más nuevo primero, que es lo que suele
     * necesitar mirarse. No es una cola de trabajo, a diferencia de las
     * protectoras pendientes y los reportes, que van al revés.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<UsuarioAdminResponse> listarUsuarios(Rol rol, Boolean activo,
                                                               Pageable paginacion) {

        PageRequest peticion = PageRequest.of(
                Math.max(0, paginacion.getPageNumber()),
                Math.min(Math.max(1, paginacion.getPageSize()), MAXIMO_POR_PAGINA),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<Usuario> pagina = usuarioRepository.findAll(UsuarioAdminSpec.de(rol, activo), peticion);

        return PaginaResponse.de(pagina, AdminService::aUsuarioAdminResponse);
    }

    /**
     * Bloquea una cuenta (RF-11): deja de poder iniciar sesión.
     *
     * QUIÉN LO HACE CUMPLIR NO ES ESTE MÉTODO. Aquí solo se pone el flag a false;
     * quien impide entrar es AuthService.verificarCuentaOperativa, que ya
     * rechazaba las cuentas inactivas con 403 CUENTA_BLOQUEADA desde el bloque de
     * autenticación, y JwtAuthFilter, que recarga el usuario en cada petición y
     * por tanto invalida de inmediato los tokens ya emitidos. Un bloqueo surte
     * efecto sin esperar a que caduque nada.
     *
     * Es idempotente: bloquear una cuenta ya bloqueada no cambia nada ni vuelve a
     * notificar. Repetir la acción no debe generarle al usuario un segundo aviso
     * de algo que ya sabía.
     *
     * NADIE SE BLOQUEA A SÍ MISMO. Es la única puerta sin retorno de este
     * service: una vez bloqueado no se puede iniciar sesión, así que un
     * administrador que se bloqueara perdería el acceso al panel y con él la
     * capacidad de desbloquearse. Solo saldría de ahí tocando la base de datos a
     * mano.
     */
    @Transactional
    public UsuarioAdminResponse bloquear(Long usuarioId, String motivo, Long adminId) {
        if (usuarioId.equals(adminId)) {
            throw new AutobloqueoException(
                    "No puedes bloquear tu propia cuenta: perderías el acceso al panel");
        }

        Usuario usuario = exigirUsuario(usuarioId);

        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            log.debug("El usuario {} ya estaba bloqueado; la operación no cambia nada", usuarioId);
            return aUsuarioAdminResponse(usuario);
        }

        usuario.setActivo(false);
        log.warn("El administrador {} ha bloqueado al usuario {}. Motivo: {}",
                adminId, usuarioId, motivo);

        notificar(usuario, TipoNotificacion.CUENTA_BLOQUEADA, null);
        return aUsuarioAdminResponse(usuario);
    }

    /** Revierte el bloqueo (RF-11). Idempotente, igual que bloquear. */
    @Transactional
    public UsuarioAdminResponse desbloquear(Long usuarioId, Long adminId) {
        Usuario usuario = exigirUsuario(usuarioId);

        if (Boolean.TRUE.equals(usuario.getActivo())) {
            log.debug("El usuario {} ya estaba activo; la operación no cambia nada", usuarioId);
            return aUsuarioAdminResponse(usuario);
        }

        usuario.setActivo(true);
        log.info("El administrador {} ha desbloqueado al usuario {}", adminId, usuarioId);

        notificar(usuario, TipoNotificacion.CUENTA_DESBLOQUEADA, null);
        return aUsuarioAdminResponse(usuario);
    }

    // ---------------------------------------------------------------------
    // Verificación de protectoras (RF-11)
    // ---------------------------------------------------------------------

    /**
     * Cola de protectoras esperando verificación (RF-11).
     *
     * Más antiguas primero: es trabajo por atender, y una protectora PENDIENTE no
     * puede operar hasta que alguien la mire.
     */
    @Transactional(readOnly = true)
    public List<ProtectoraAdminResponse> listarPendientes() {
        return protectoraRepository.buscarPorEstadoConCuenta(EstadoProtectora.PENDIENTE).stream()
                .map(AdminService::aProtectoraAdminResponse)
                .toList();
    }

    /**
     * Aprueba una protectora: pasa a ACTIVA y ya puede operar (RF-11).
     *
     * EL SWITCH ES EXHAUSTIVO Y SIN RAMA POR DEFECTO, igual que el de
     * ProtectoraPanelService.exigirProtectoraActiva y por el mismo motivo: si
     * mañana se añade un estado al enum, esto deja de compilar en lugar de tomar
     * una decisión silenciosa sobre un caso que nadie pensó.
     *
     * Desde RECHAZADA también se aprueba, a propósito. Un rechazo no es una
     * condena: la protectora puede haber aportado la documentación que faltaba, y
     * sin este camino la única salida sería registrarse otra vez, perdiendo la
     * cuenta y todo lo que colgara de ella.
     */
    @Transactional
    public ProtectoraAdminResponse aprobarProtectora(Long protectoraId, Long adminId) {
        Protectora protectora = exigirProtectora(protectoraId);

        return switch (protectora.getEstado()) {
            case ACTIVA -> {
                log.debug("La protectora {} ya estaba activa; la operación no cambia nada",
                        protectoraId);
                yield aProtectoraAdminResponse(protectora);
            }

            case PENDIENTE, RECHAZADA -> {
                protectora.setEstado(EstadoProtectora.ACTIVA);
                log.info("El administrador {} ha aprobado la protectora {}", adminId, protectoraId);

                notificar(protectora.getUsuario(), TipoNotificacion.PROTECTORA_VERIFICADA,
                        protectoraId);
                yield aProtectoraAdminResponse(protectora);
            }
        };
    }

    /**
     * Rechaza una protectora: pasa a RECHAZADA y no puede operar (RF-11).
     *
     * Desde ACTIVA también, y no es un caso raro sino el de retirar una
     * verificación ya concedida cuando la organización deja de merecerla. Surte
     * efecto de inmediato: ProtectoraPanelService comprueba el estado en CADA
     * petición, precisamente para que un token emitido mientras estaba activa no
     * la deje seguir gestionando su panel hasta caducar.
     */
    @Transactional
    public ProtectoraAdminResponse rechazarProtectora(Long protectoraId, String motivo,
                                                      Long adminId) {

        Protectora protectora = exigirProtectora(protectoraId);

        return switch (protectora.getEstado()) {
            case RECHAZADA -> {
                log.debug("La protectora {} ya estaba rechazada; la operación no cambia nada",
                        protectoraId);
                yield aProtectoraAdminResponse(protectora);
            }

            case PENDIENTE, ACTIVA -> {
                protectora.setEstado(EstadoProtectora.RECHAZADA);
                log.warn("El administrador {} ha rechazado la protectora {}. Motivo: {}",
                        adminId, protectoraId, motivo);

                notificar(protectora.getUsuario(), TipoNotificacion.PROTECTORA_RECHAZADA,
                        protectoraId);
                yield aProtectoraAdminResponse(protectora);
            }
        };
    }

    // ---------------------------------------------------------------------
    // Reportes (RF-11, RF-26)
    // ---------------------------------------------------------------------

    /**
     * Reportes pendientes de revisar (RF-11).
     *
     * DEVUELVE SIEMPRE VACÍO HOY, y no porque esté a medias: la consulta es real
     * y funciona, pero nada puede crear reportes todavía. El endpoint de reportar
     * contenido y ReporteService son del bloque 9 (RF-26). En cuanto existan,
     * esto empieza a devolver datos sin tocarlo. Ver ReporteResponse.
     */
    @Transactional(readOnly = true)
    public List<ReporteResponse> listarReportesPendientes() {
        return reporteRepository.buscarPorEstadoConAutor(EstadoReporte.PENDIENTE).stream()
                .map(AdminService::aReporteResponse)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Retirada de publicaciones (RF-11)
    // ---------------------------------------------------------------------

    /**
     * Retira una publicación que incumple las normas (RF-11).
     *
     * HACE FALTA EL TIPO ADEMÁS DEL ID, y no es un capricho de la firma: el id de
     * una publicación solo es único dentro de su tabla, así que el 7 existe a la
     * vez como alerta, como avistamiento y como animal. Sin discriminador este
     * endpoint no puede saber qué se le está pidiendo retirar. TipoContenido
     * existe exactamente para esto —lo dice docs/api-rest.md al justificar por qué
     * se añadió— y es también lo que guarda REPORTE junto a su objetivo, de modo
     * que el administrador puede pasar aquí el par que le llega del reporte.
     *
     * NINGUNO DE LOS TRES SE BORRA. Cada tabla tiene su estado terminal y se usa
     * el suyo: ELIMINADA en alerta, ELIMINADO en avistamiento, RETIRADO en animal.
     * Borrar de verdad dejaría huérfanos los MATCH_REGISTRO que apuntan a la
     * publicación —referencia polimórfica, sin clave ajena que lo impida— y con
     * ellos su chat y sus mensajes, que desaparecerían de la lista de su dueño sin
     * un solo aviso. El razonamiento completo está en EstadoAvistamiento.
     *
     * El switch es exhaustivo y sin rama por defecto: un TipoContenido nuevo
     * rompe la compilación aquí en lugar de caer en un "no hago nada" silencioso.
     *
     * Es idempotente en los tres casos: retirar lo ya retirado no cambia nada.
     */
    @Transactional
    public void retirarPublicacion(TipoContenido tipo, Long publicacionId, Long adminId) {
        switch (tipo) {
            case ALERTA -> retirarAlerta(publicacionId, adminId);
            case AVISTAMIENTO -> retirarAvistamiento(publicacionId, adminId);
            case ADOPCION -> retirarAnimal(publicacionId, adminId);
        }
    }

    private void retirarAlerta(Long alertaId, Long adminId) {
        Alerta alerta = alertaRepository.findById(alertaId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La alerta " + alertaId + " no existe"));

        if (alerta.getEstado() == EstadoAlerta.ELIMINADA) {
            log.debug("La alerta {} ya estaba eliminada", alertaId);
            return;
        }

        alerta.setEstado(EstadoAlerta.ELIMINADA);
        log.warn("El administrador {} ha retirado la alerta {} por incumplir las normas",
                adminId, alertaId);
    }

    private void retirarAvistamiento(Long avistamientoId, Long adminId) {
        Avistamiento avistamiento = avistamientoRepository.findById(avistamientoId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El avistamiento " + avistamientoId + " no existe"));

        if (avistamiento.getEstado() == EstadoAvistamiento.ELIMINADO) {
            log.debug("El avistamiento {} ya estaba eliminado", avistamientoId);
            return;
        }

        avistamiento.setEstado(EstadoAvistamiento.ELIMINADO);
        log.warn("El administrador {} ha retirado el avistamiento {} por incumplir las normas",
                adminId, avistamientoId);
    }

    private void retirarAnimal(Long animalId, Long adminId) {
        AnimalAdopcion animal = animalAdopcionRepository.findById(animalId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El animal " + animalId + " no existe"));

        if (animal.getEstado() == EstadoAnimal.RETIRADO) {
            log.debug("El animal {} ya estaba retirado", animalId);
            return;
        }

        // Se usa RETIRADO, el mismo estado con el que una protectora saca un
        // animal de su catálogo, y no uno nuevo para la retirada por moderación.
        // Para todo lo que lee el catálogo la consecuencia es idéntica —deja de
        // ofrecerse— y quién lo retiró queda en el log. Un estado más solo
        // añadiría una rama que ninguna consulta necesita distinguir.
        animal.setEstado(EstadoAnimal.RETIRADO);
        log.warn("El administrador {} ha retirado el animal {} por incumplir las normas",
                adminId, animalId);
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private Usuario exigirUsuario(Long usuarioId) {
        return usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El usuario " + usuarioId + " no existe"));
    }

    private Protectora exigirProtectora(Long protectoraId) {
        return protectoraRepository.buscarConCuenta(protectoraId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La protectora " + protectoraId + " no existe"));
    }

    /**
     * Avisa al afectado, sin dejar que un fallo del aviso deshaga la decisión.
     *
     * NotificacionService abre su propia transacción para esto, así que un error
     * insertando la notificación no arrastra a la del bloqueo o la aprobación.
     * El try/catch cubre la otra mitad: que la excepción no suba hasta aquí y
     * tumbe igualmente la operación. Es el mismo trato que AlertaService da al
     * aviso a la zona, y por el mismo motivo: la decisión del administrador vale
     * por sí sola, el aviso es su consecuencia.
     */
    private void notificar(Usuario destinatario, TipoNotificacion tipo, Long referenciaId) {
        try {
            notificacionService.notificarDecisionAdministrativa(destinatario, tipo, referenciaId);
        } catch (RuntimeException e) {
            log.error("No se pudo notificar {} al usuario {}", tipo, destinatario.getId(), e);
        }
    }

    private static UsuarioAdminResponse aUsuarioAdminResponse(Usuario usuario) {
        return UsuarioAdminResponse.builder()
                .id(usuario.getId())
                .nombre(usuario.getNombre())
                .email(usuario.getEmail())
                .rol(usuario.getRol())
                .activo(usuario.getActivo())
                .areaGeografica(usuario.getAreaGeografica())
                .createdAt(usuario.getCreatedAt())
                .build();
    }

    private static ProtectoraAdminResponse aProtectoraAdminResponse(Protectora protectora) {
        Usuario cuenta = protectora.getUsuario();

        return ProtectoraAdminResponse.builder()
                .id(protectora.getId())
                .nombreOrganizacion(protectora.getNombreOrganizacion())
                .direccion(protectora.getDireccion())
                .zonaGeografica(protectora.getZonaGeografica())
                .estado(protectora.getEstado())
                .usuarioId(cuenta.getId())
                .usuarioNombre(cuenta.getNombre())
                .usuarioEmail(cuenta.getEmail())
                .createdAt(protectora.getCreatedAt())
                .build();
    }

    private static ReporteResponse aReporteResponse(Reporte reporte) {
        Usuario autor = reporte.getUsuario();

        return ReporteResponse.builder()
                .id(reporte.getId())
                .autor(AutorResponse.builder()
                        .id(autor.getId())
                        .nombre(autor.getNombre())
                        .fotoUrl(autor.getFotoUrl())
                        .build())
                .tipoObjetivo(reporte.getTipoObjetivo())
                .objetivoId(reporte.getObjetivoId())
                .tipoContenido(reporte.getTipoContenido())
                .motivo(reporte.getMotivo())
                .estado(reporte.getEstado())
                .createdAt(reporte.getCreatedAt())
                .build();
    }
}
