package com.rescateanimal.backend.model.enums;

/**
 * Visibilidad de un avistamiento (RF-21, RF-11).
 *
 * POR QUÉ UN AVISTAMIENTO SE RETIRA Y NO SE BORRA. Cuando el administrador
 * elimina una publicación que incumple las normas (RF-11), la fila se conserva y
 * pasa a ELIMINADO, igual que una alerta pasa a ELIMINADA y un animal a RETIRADO.
 *
 * El motivo es el mismo que llevó a RETIRADO, y aquí es incluso más grave:
 * MATCH_REGISTRO.publicacion_id apunta al avistamiento sin clave ajena que lo
 * respalde, así que un DELETE físico dejaría huérfanos sus matches y, con ellos,
 * el chat y los mensajes que colgaran de cada uno. Peor todavía, ese chat no
 * daría error: ChatRepository.buscarPorParticipante resuelve al autor del
 * avistamiento con un EXISTS contra esta tabla, de modo que al desaparecer la
 * fila la conversación sencillamente dejaría de aparecer en su lista de chats.
 * Alguien perdería una conversación en curso sin aviso y sin poder releer lo ya
 * hablado.
 *
 * Y moderar no exige borrar: exige que el contenido deje de verse. Un estado
 * consigue eso y además conserva constancia de qué se retiró, que es justo lo
 * que un administrador necesita para justificar la decisión si se reclama
 * (RF-26).
 *
 * DÓNDE FILTRA Y DÓNDE NO. El feed de swipe excluye los ELIMINADO y MatchService
 * rechaza deslizarlos; el chat, en cambio, sigue resolviéndolos a propósito, que
 * es precisamente lo que evita que las conversaciones se evaporen.
 *
 * ESTO NO ES EL BORRADO DEL RGPD. Retirar contenido que incumple las normas y
 * borrar los datos de una persona que ejerce su derecho de supresión son cosas
 * distintas: la segunda es RF-19 y pertenece al bloque 10, donde sí habrá que
 * decidir qué se elimina de verdad.
 */
public enum EstadoAvistamiento {

    /** Estado normal: se ve en el feed y admite matches. */
    VISIBLE,

    /** Retirado por el administrador (RF-11). Sale del feed, pero su chat sigue vivo. */
    ELIMINADO
}
