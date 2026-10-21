package com.rescateanimal.backend.service;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Lógica de notificaciones: alta, listado y marcado como leídas (RF-05).
 */
@Slf4j
@Service
public class NotificacionService {

    private final NotificacionRepository notificacionRepository;
    private final UsuarioRepository usuarioRepository;

    /**
     * Transacción propia para los avisos que se crean tras el commit de la
     * operación que los origina (ver trasConfirmar). REQUIRES_NEW no es una
     * preferencia: desde afterCommit, la transacción de quien llama ya está
     * confirmada pero sus recursos siguen enlazados al hilo, y cualquier
     * escritura que no abra una nueva se colgaría de esa y no se confirmaría
     * nunca (javadoc de TransactionSynchronization.afterCommit).
     */
    private final TransactionTemplate nuevaTransaccion;

    public NotificacionService(NotificacionRepository notificacionRepository,
                               UsuarioRepository usuarioRepository,
                               PlatformTransactionManager transacciones) {
        this.notificacionRepository = notificacionRepository;
        this.usuarioRepository = usuarioRepository;
        this.nuevaTransaccion = new TransactionTemplate(transacciones);
        this.nuevaTransaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Avisa al dueño de una alerta de que alguien ha registrado un avistamiento
     * en ella (NUEVO_AVISTAMIENTO), una vez confirmado el avistamiento.
     *
     * referenciaId es la ALERTA, no el avistamiento: el aviso lleva al feed de
     * esa alerta, donde el avistamiento nuevo sale el primero, sin depender de
     * un GET /api/avistamientos/{id} que no existe; y si el administrador retira
     * después el avistamiento, el aviso sigue llevando a un sitio válido.
     *
     * Nadie recibe aviso de lo que acaba de hacer: si el autor del avistamiento
     * es el propio dueño, no se crea nada. Tampoco se avisa a una cuenta
     * inactiva, el mismo criterio que ALERTA_CERCANA.
     */
    public void avisarNuevoAvistamiento(Long alertaId, Long duenoId, Long autorDelAvistamientoId) {
        if (duenoId.equals(autorDelAvistamientoId)) {
            log.debug("Alerta {}: el avistamiento es de su dueño, no se le avisa", alertaId);
            return;
        }
        trasConfirmar("NUEVO_AVISTAMIENTO de la alerta " + alertaId, () ->
                usuarioRepository.findById(duenoId)
                        .filter(dueno -> Boolean.TRUE.equals(dueno.getActivo()))
                        .ifPresent(dueno -> {
                            notificacionRepository.save(
                                    crear(dueno, TipoNotificacion.NUEVO_AVISTAMIENTO, alertaId));
                            log.info("Alerta {}: creada notificación NUEVO_AVISTAMIENTO para su dueño {}",
                                    alertaId, duenoId);
                        }));
    }

    /**
     * Avisa con ALERTA_ENCONTRADA a quienes tienen un chat abierto por un
     * avistamiento de la alerta, una vez confirmado el cierre (caso de uso de
     * cerrar alerta del SRS). referenciaId es la alerta: su detalle responde 200
     * con el estado ENCONTRADA.
     *
     * Los destinatarios se buscan ya dentro de la transacción del aviso, tras el
     * commit, y no antes: así cuentan todos los matches confirmados hasta el
     * cierre. Quiénes son exactamente lo explica
     * UsuarioRepository.buscarParticipantesDeChatsDeLaAlerta.
     *
     * @param duenoId quien cierra la alerta, que es su dueño: no se le avisa de
     *                lo que acaba de hacer
     */
    public void avisarAlertaEncontrada(Long alertaId, Long duenoId) {
        trasConfirmar("ALERTA_ENCONTRADA de la alerta " + alertaId, () -> {
            List<Notificacion> avisos = usuarioRepository
                    .buscarParticipantesDeChatsDeLaAlerta(alertaId, duenoId, TipoPublicacion.AVISTAMIENTO)
                    .stream()
                    .map(destinatario -> crear(destinatario, TipoNotificacion.ALERTA_ENCONTRADA, alertaId))
                    .toList();
            notificacionRepository.saveAll(avisos);
            log.info("Alerta {}: creadas {} notificaciones ALERTA_ENCONTRADA", alertaId, avisos.size());
        });
    }

    /**
     * Crea un aviso DESPUÉS de que se confirme la transacción de quien llama,
     * como los eventos del mapa (AlertaService.publicarEnMapa).
     *
     * Por qué después: un aviso es la consecuencia de algo que ya ha pasado. Si
     * se creara antes y la operación terminara en rollback, quedaría un aviso
     * apuntando con su referencia_id a algo que nunca llegó a existir (la
     * limitación que documentaba §12 del contrato). Aquí, sin commit no hay
     * aviso.
     *
     * Y por qué en su propia transacción y protegido: la operación ya está
     * confirmada y es lo que el usuario pidió, así que un fallo creando el aviso
     * no puede deshacerla ni llegarle como error. Se registra en el log.
     *
     * Sin una transacción activa —una llamada desde fuera de cualquier
     * transacción, o una prueba unitaria— no hay commit que esperar y se crea en
     * el momento.
     */
    private void trasConfirmar(String aviso, Runnable escritura) {
        Runnable protegida = () -> {
            try {
                nuevaTransaccion.executeWithoutResult(estado -> escritura.run());
            } catch (RuntimeException e) {
                log.error("No se pudo crear el aviso {}", aviso, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    protegida.run();
                }
            });
        } else {
            protegida.run();
        }
    }

    /**
     * Registra una notificación para cada usuario del área donde se ha publicado
     * una alerta, excluyendo a quien la publicó (RF-05).
     *
     * ALCANCE: por ahora solo persiste la notificación. El envío push real
     * (Firebase u otro proveedor) se conectará cuando exista la aplicación
     * Android que lo consuma; hasta entonces el cliente las recibe consultando
     * GET /api/notificaciones.
     *
     * LIMITACIÓN DEL MODELO: la cercanía se resuelve comparando el texto de
     * Usuario.areaGeografica con el del autor de la alerta, porque el SRS define
     * ese campo como una cadena y no como un punto. Una proximidad real —por
     * ejemplo, todos los usuarios a menos de 5 km de la alerta— exigiría añadir
     * una coordenada a USUARIO o geocodificar el área, que es un cambio de
     * modelo pendiente de acordar.
     *
     * TRAS EL COMMIT (trasConfirmar). Antes se creaban en una transacción propia
     * que se confirmaba ANTES que la de publicar, y si aquella fallaba al
     * confirmarse quedaban avisos huérfanos apuntando a una alerta que nunca
     * existió. Ahora el aviso solo se crea con la alerta ya confirmada, y un
     * fallo creándolo no tumba la publicación, que es lo que el usuario pidió.
     *
     * El área y los ids se leen ahora, con la entidad todavía gestionada; la
     * escritura, después, en una transacción en la que esa entidad ya no está.
     */
    public void notificarAlertaCercana(Alerta alerta) {
        String area = alerta.getUsuario().getAreaGeografica();
        Long alertaId = alerta.getId();
        if (area == null || area.isBlank()) {
            log.debug("La alerta {} no tiene área asociada: no se notifica a nadie", alertaId);
            return;
        }
        Long autorId = alerta.getUsuario().getId();

        trasConfirmar("ALERTA_CERCANA de la alerta " + alertaId, () -> {
            List<Notificacion> notificaciones = usuarioRepository
                    .findByAreaGeograficaIgnoreCaseAndActivoTrueAndIdNot(area, autorId)
                    .stream()
                    .map(destinatario -> crear(destinatario, TipoNotificacion.ALERTA_CERCANA, alertaId))
                    .toList();
            notificacionRepository.saveAll(notificaciones);
            log.info("Alerta {}: creadas {} notificaciones de tipo ALERTA_CERCANA",
                    alertaId, notificaciones.size());
        });
    }

    /**
     * Avisa a quien registró el avistamiento de que alguien ha hecho match con
     * él (RF-08). referenciaId apunta al match, que es desde donde el cliente
     * llega al chat.
     *
     * EL DESTINATARIO ES EL AUTOR DEL AVISTAMIENTO, no el dueño de la alerta.
     * Son los dos extremos del chat que abre el match (RF-12): quien desliza y
     * quien publicó lo deslizado. Quien desliza un avistamiento es siempre el
     * dueño de su alerta (MatchService), así que notificarle a él sería avisar a
     * quien acababa de hacer el match.
     *
     * ESTE AVISO NO VA TRAS EL COMMIT, al contrario que todos los demás, y es a
     * propósito: un match del que el destinatario nunca se entera no sirve de
     * nada, así que la notificación forma parte de la operación y comparte su
     * suerte —match, chat y aviso, o ninguno—. Los demás avisos son
     * consecuencias de algo que vale por sí solo aunque el aviso falle.
     *
     * @return la notificación creada, o vacío si el destinatario es el propio
     *         autor del deslizamiento: nadie necesita que le avisen de lo que
     *         acaba de hacer. Con el destinatario actual esa situación ya la
     *         corta antes PublicacionPropiaException —nadie desliza lo suyo—,
     *         pero la guarda se mantiene porque es barata y deja de depender de
     *         una validación que vive en otro service.
     */
    @Transactional
    public Optional<Notificacion> notificarNuevoMatch(Usuario destinatario, Long matchId,
                                                      Long autorDelDeslizamientoId) {
        if (destinatario.getId().equals(autorDelDeslizamientoId)) {
            log.debug("Match {}: el destinatario es quien deslizó, no se notifica", matchId);
            return Optional.empty();
        }

        Notificacion notificacion = notificacionRepository.save(
                crear(destinatario, TipoNotificacion.NUEVO_MATCH, matchId));
        log.info("Match {}: creada notificación NUEVO_MATCH para el usuario {}",
                matchId, destinatario.getId());
        return Optional.of(notificacion);
    }

    /**
     * Avisa a un usuario de una decisión que el administrador ha tomado sobre su
     * cuenta, su protectora o su reporte (RF-11, RF-26): aprobación, rechazo,
     * bloqueo, desbloqueo o reporte resuelto.
     *
     * UN SOLO MÉTODO PARA TODOS LOS CASOS, y no uno por tipo. Aquí, a
     * diferencia de las alertas o los matches, no hay nada que decidir por tipo:
     * el destinatario ya viene resuelto por quien toma la decisión y lo único
     * que cambia es la constante.
     *
     * TRAS EL COMMIT (trasConfirmar): la decisión del administrador vale por sí
     * sola y el aviso es su consecuencia. Un fallo creándolo no deshace un
     * bloqueo —la cuenta abusiva no se queda operativa por culpa del aviso— y,
     * al revés, una decisión que se deshace no deja un aviso de algo que no
     * pasó. Antes se creaba en una transacción propia que se confirmaba antes
     * que la de la decisión, y ese segundo caso sí podía darse.
     *
     * Se avisa aunque la cuenta esté inactiva: es justo el caso de
     * CUENTA_BLOQUEADA, un aviso diferido que el usuario lee al desbloquearse.
     *
     * EL MOTIVO NO VIAJA AQUÍ. NOTIFICACION no tiene ningún campo de texto, así
     * que lo que el administrador escribió queda en el log. Ver
     * BloquearUsuarioRequest.motivo.
     *
     * @param referenciaId el recurso al que se refiere la decisión: la protectora
     *                     al aprobar o rechazar, el reporte al resolverlo, y nulo
     *                     al bloquear o desbloquear una cuenta, porque el afectado
     *                     es el propio destinatario y no hay nada más a lo que
     *                     apuntar
     */
    public void notificarDecisionAdministrativa(Usuario destinatario, TipoNotificacion tipo,
                                                Long referenciaId) {
        Long destinatarioId = destinatario.getId();
        trasConfirmar(tipo + " para el usuario " + destinatarioId, () -> {
            notificacionRepository.save(
                    crear(usuarioRepository.getReferenceById(destinatarioId), tipo, referenciaId));
            log.info("Creada notificación {} para el usuario {}", tipo, destinatarioId);
        });
    }

    private Notificacion crear(Usuario destinatario, TipoNotificacion tipo, Long referenciaId) {
        Notificacion notificacion = new Notificacion();
        notificacion.setUsuario(destinatario);
        notificacion.setTipo(tipo);
        notificacion.setReferenciaId(referenciaId);
        notificacion.setLeida(false);
        return notificacion;
    }
}
