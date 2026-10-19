package com.rescateanimal.backend.model.enums;

/**
 * Tipo de notificación recibida por un usuario (RF-05).
 * El campo referenciaId de Notificacion apunta al elemento que la origina,
 * y su significado depende de este tipo.
 *
 * NOTA: el SRS define este campo como ENUM pero no enumera sus valores.
 * Estos son una propuesta inicial pendiente de confirmación.
 */
public enum TipoNotificacion {
    ALERTA_CERCANA,
    NUEVO_MATCH,
    NUEVO_MENSAJE,
    PROTECTORA_VERIFICADA,
    ADOPCION_CONFIRMADA,
    REPORTE_RESUELTO,

    // -----------------------------------------------------------------------
    // Decisiones del administrador (RF-11). Añadidos con la migración V6, que
    // amplía el CHECK de notificacion.tipo: sin ella la fila no entraría.
    //
    // NO LLEVAN EL MOTIVO, y conviene saberlo: NOTIFICACION solo tiene tipo y
    // referencia_id, sin texto libre, así que el motivo que el administrador
    // escribe al bloquear o rechazar queda en el log y no llega al usuario.
    // Hacerlo llegar exige una columna de texto en NOTIFICACION o una tabla de
    // auditoría, y ninguna de las dos se ha decidido todavía.
    // -----------------------------------------------------------------------

    /** La solicitud de la protectora fue rechazada por el administrador. */
    PROTECTORA_RECHAZADA,

    /**
     * La cuenta ha sido bloqueada.
     *
     * Su destinatario no puede iniciar sesión, así que no la leerá mientras dure
     * el bloqueo: queda esperándole y cobra sentido al desbloquearse, como
     * constancia de lo que pasó. No es un aviso inútil, es un aviso diferido.
     */
    CUENTA_BLOQUEADA,

    /** La cuenta vuelve a estar operativa. Esta sí se lee en cuanto entra. */
    CUENTA_DESBLOQUEADA
}
