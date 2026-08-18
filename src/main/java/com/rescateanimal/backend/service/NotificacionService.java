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

    private Notificacion crear(Usuario destinatario, TipoNotificacion tipo, Long referenciaId) {
        Notificacion notificacion = new Notificacion();
        notificacion.setUsuario(destinatario);
        notificacion.setTipo(tipo);
        notificacion.setReferenciaId(referenciaId);
        notificacion.setLeida(false);
        return notificacion;
    }
}
