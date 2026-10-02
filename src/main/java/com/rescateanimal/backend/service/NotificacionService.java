package com.rescateanimal.backend.service;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Lógica de notificaciones: alta, listado y marcado como leídas (RF-05).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificacionService {

    private final NotificacionRepository notificacionRepository;
    private final UsuarioRepository usuarioRepository;

    /**
     * Registra una notificación para cada usuario del área donde se ha publicado
     * una alerta, excluyendo a quien la publicó (RF-05).
     *
     * ALCANCE: por ahora solo persiste la notificación. El envío push real
     * (Firebase u otro proveedor) se conectará cuando exista la aplicación
     * Android que lo consuma; hasta entonces el cliente las recibe consultando
     * /api/notificaciones o por el canal /ws/notificaciones.
     *
     * LIMITACIÓN DEL MODELO: la cercanía se resuelve comparando el texto de
     * Usuario.areaGeografica con el del autor de la alerta, porque el SRS define
     * ese campo como una cadena y no como un punto. Una proximidad real —por
     * ejemplo, todos los usuarios a menos de 5 km de la alerta— exigiría añadir
     * una coordenada a USUARIO o geocodificar el área, que es un cambio de
     * modelo pendiente de acordar.
     *
     * TRANSACCIÓN PROPIA (REQUIRES_NEW): esta es una consecuencia de publicar una
     * alerta, no parte de publicarla. Compartiendo transacción con
     * AlertaService.publicar, un fallo aquí marcaría esa transacción como
     * "rollback only" y la alerta —que ya estaba guardada y es lo que el usuario
     * pidió— se perdería. La entidad que llega ya tiene cargados su id y su
     * autor, así que no hace falta inicializar nada perezoso desde esta
     * transacción nueva.
     *
     * LIMITACIÓN ACEPTADA: EL AVISO PUEDE SOBREVIVIR A LA ALERTA. Esta
     * transacción se confirma antes que la de publicar, así que si aquella
     * fallara después, quedarían notificaciones ALERTA_CERCANA apuntando con su
     * referencia_id a una alerta que nunca llegó a existir; el cliente que abra
     * una recibiría un 404. Es el mismo riesgo simétrico que se acepta en
     * notificarDecisionAdministrativa, pero aquí no se corrige solo: la
     * notificación queda huérfana para siempre. Se acepta porque es remoto:
     * Alerta usa IDENTITY, de modo que el INSERT ya se ejecutó en save() y
     * cualquier CHECK, NOT NULL o clave ajena habría fallado antes de llegar
     * aquí; solo queda que falle el propio commit (conexión perdida, caída de la
     * base de datos). Y no puede apuntar a la alerta equivocada: un id de
     * IDENTITY consumido no se reutiliza aunque la transacción se deshaga.
     * Si hubiera que cerrarlo, la llamada se movería a un afterCommit, como el
     * evento del mapa en AlertaService.publicarEnMapa: el aviso solo se crearía
     * con la alerta ya confirmada.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int notificarAlertaCercana(Alerta alerta) {
        String area = alerta.getUsuario().getAreaGeografica();
        if (area == null || area.isBlank()) {
            log.debug("La alerta {} no tiene área asociada: no se notifica a nadie", alerta.getId());
            return 0;
        }

        List<Usuario> destinatarios = usuarioRepository
                .findByAreaGeograficaIgnoreCaseAndActivoTrueAndIdNot(area, alerta.getUsuario().getId());

        List<Notificacion> notificaciones = destinatarios.stream()
                .map(destinatario -> crear(destinatario, TipoNotificacion.ALERTA_CERCANA, alerta.getId()))
                .toList();

        notificacionRepository.saveAll(notificaciones);
        log.info("Alerta {}: creadas {} notificaciones de tipo ALERTA_CERCANA",
                alerta.getId(), notificaciones.size());
        return notificaciones.size();
    }

    /**
     * Avisa a quien registró el avistamiento de que alguien ha hecho match con
     * él (RF-08). referenciaId apunta al match, que es desde donde el cliente
     * llega al chat.
     *
     * EL DESTINATARIO ES EL AUTOR DEL AVISTAMIENTO, no el dueño de la alerta.
     * Son los dos extremos del chat que abre el match (RF-12): quien desliza y
     * quien publicó lo deslizado. El dueño de la alerta es habitualmente quien
     * desliza —el feed solo le esconde sus propios avistamientos, no los que
     * otros registran sobre su alerta—, así que notificarle a él era avisar a
     * quien acababa de hacer el match.
     *
     * Aquí NO se usa REQUIRES_NEW, al contrario que en notificarAlertaCercana:
     * un match del que el destinatario nunca se entera no sirve de nada, así
     * que la notificación forma parte de la operación y comparte su suerte. La
     * alerta, en cambio, es útil por sí sola aunque falle el aviso a la zona.
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
     * cuenta o sobre su protectora (RF-11): aprobación, rechazo, bloqueo o
     * desbloqueo.
     *
     * UN SOLO MÉTODO PARA LOS CUATRO CASOS, y no cuatro. Aquí, a diferencia de
     * las alertas o los matches, no hay nada que decidir por tipo: el
     * destinatario ya viene resuelto por quien toma la decisión y lo único que
     * cambia es la constante. Cuatro métodos idénticos salvo por un literal
     * habrían sido cuatro sitios donde equivocarse.
     *
     * TRANSACCIÓN PROPIA (REQUIRES_NEW), como en notificarAlertaCercana y por el
     * mismo motivo, que aquí pesa más todavía: bloquear una cuenta abusiva es lo
     * que el administrador ha pedido, y avisar al afectado es la consecuencia.
     * Compartiendo transacción, un fallo insertando la notificación marcaría la
     * del bloqueo como "rollback only" y la cuenta se quedaría operativa. El
     * riesgo simétrico —que el aviso sobreviva a una decisión que sí se
     * deshace— es remoto y se corrige solo: lo que el usuario puede hacer
     * depende de su fila, no del aviso.
     *
     * EL MOTIVO NO VIAJA AQUÍ. NOTIFICACION no tiene ningún campo de texto, así
     * que lo que el administrador escribió queda en el log. Ver
     * BloquearUsuarioRequest.motivo.
     *
     * @param referenciaId el recurso al que se refiere la decisión: la protectora
     *                     al aprobar o rechazar, y nulo al bloquear o desbloquear
     *                     una cuenta, porque el afectado es el propio destinatario
     *                     y no hay nada más a lo que apuntar
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notificacion notificarDecisionAdministrativa(Usuario destinatario, TipoNotificacion tipo,
                                                        Long referenciaId) {

        Notificacion notificacion = notificacionRepository.save(
                crear(destinatario, tipo, referenciaId));
        log.info("Creada notificación {} para el usuario {}", tipo, destinatario.getId());
        return notificacion;
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
