package com.rescateanimal.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Registro de auditoría de los eventos de autenticación (informe ENS, C2):
 * qué pasó, a quién, desde dónde y con qué resultado. El cuándo es la marca de
 * tiempo de la línea.
 *
 * UNA LÍNEA POR EVENTO, EN SU PROPIO LOGGER Y CON CLAVE=VALOR:
 *
 *   evento=LOGIN resultado=CREDENCIALES_INVALIDAS usuario=42 ip=203.0.113.7
 *
 * El logger, com.rescateanimal.backend.auditoria, cuelga del paquete del
 * proyecto, así que el perfil prod lo escribe (INFO), y tiene nombre propio
 * para poder filtrarlo, llevarlo a otro destino o alertar sobre él sin
 * mezclarlo con la depuración. Los éxitos van en INFO; los rechazos, en WARN.
 *
 * QUÉ NO LLEGA NUNCA: el email, la contraseña ni el token. El usuario va por
 * su id, y cuando no hay cuenta —un login con un email que no existe— va "-":
 * el email que se probó es un dato personal, y de alguien que puede no ser
 * quien lo tecleó.
 *
 * LA IP ES LA DE LA PETICIÓN EN CURSO, la que calcula Tomcat (ver C1 en
 * application-prod.properties). Fuera de una petición —una tarea en segundo
 * plano— va "-".
 */
@Service
public class AuditoriaService {

    static final String LOGGER = "com.rescateanimal.backend.auditoria";

    private static final Logger AUDITORIA = LoggerFactory.getLogger(LOGGER);

    /** Qué ha pasado. */
    public enum Evento {
        REGISTRO, LOGIN, LOGOUT, RECUPERACION_SOLICITADA, PASSWORD_RESTABLECIDA, LIMITE_INTENTOS
    }

    /** Con qué resultado. OK es el único que no es un rechazo. */
    public enum Resultado {
        OK,
        CREDENCIALES_INVALIDAS,
        CUENTA_BLOQUEADA,
        PROTECTORA_PENDIENTE,
        PROTECTORA_RECHAZADA,
        EMAIL_YA_REGISTRADO,
        SIN_CUENTA,
        TOKEN_INVALIDO,
        TOKEN_YA_USADO,
        BLOQUEADO_POR_IP,
        BLOQUEADO_POR_CUENTA
    }

    /** @param usuarioId la cuenta afectada, o null si no hay ninguna */
    public void registrar(Evento evento, Resultado resultado, Long usuarioId) {
        String usuario = usuarioId == null ? "-" : usuarioId.toString();
        if (resultado == Resultado.OK) {
            AUDITORIA.info("evento={} resultado={} usuario={} ip={}", evento, resultado, usuario, ipDeLaPeticion());
        } else {
            AUDITORIA.warn("evento={} resultado={} usuario={} ip={}", evento, resultado, usuario, ipDeLaPeticion());
        }
    }

    private static String ipDeLaPeticion() {
        RequestAttributes atributos = RequestContextHolder.getRequestAttributes();
        return atributos instanceof ServletRequestAttributes peticion
                ? peticion.getRequest().getRemoteAddr()
                : "-";
    }
}
