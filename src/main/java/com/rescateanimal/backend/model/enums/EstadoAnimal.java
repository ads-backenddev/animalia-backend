package com.rescateanimal.backend.model.enums;

/**
 * Estado de un animal del catálogo de adopción.
 *
 * Los tres son terminales salvo DISPONIBLE, que es donde nace todo animal
 * publicado (RF-07):
 *
 * - DISPONIBLE: se ofrece en el catálogo y en el feed de swipe.
 * - ADOPTADO:   la protectora confirmó la adopción (RF-20).
 * - RETIRADO:   la protectora lo quitó del catálogo (RF-07).
 *
 * RETIRADO sustituye al borrado físico de la fila, igual que ALERTA usa
 * ELIMINADA en vez de borrarse. Borrar dejaba huérfanos los MATCH_REGISTRO que
 * apuntaran al animal —publicacion_id es una referencia polimórfica sin clave
 * ajena que lo impida—, y con ellos su chat y sus mensajes: una conversación en
 * curso desaparecía del listado de golpe y sin explicación. Conservando la fila,
 * el chat sigue existiendo y el otro extremo puede leer lo que ya se dijo.
 */
public enum EstadoAnimal {
    DISPONIBLE,
    ADOPTADO,
    RETIRADO
}
