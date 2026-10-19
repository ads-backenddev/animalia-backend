package com.rescateanimal.backend.model.enums;

/**
 * Estado de una alerta de mascota perdida.
 *
 * Solo ACTIVA está viva: es la única que sale en el mapa, admite avistamientos
 * y deja hacer match sobre ellos. Las otras tres son finales, y en ninguna se
 * borra nada: los avistamientos, matches, chats y mensajes que colgaban de la
 * alerta siguen ahí, porque la conversación pertenece también a la otra parte.
 *
 * QUIÉN PONE CADA UNO, y por qué RETIRADA y ELIMINADA no son lo mismo:
 * - ENCONTRADA: el dueño, cuando el animal aparece (RF-14).
 * - RETIRADA: el dueño, que la quita sin más (DELETE /api/alertas/{id}). Es una
 *   decisión neutra.
 * - ELIMINADA: el administrador, por incumplir las normas (RF-11). Es una
 *   sanción, y por eso nada de lo que haga el dueño la sobrescribe: ni cerrar
 *   ni retirar una alerta ELIMINADA cambian su estado.
 * Si las dos retiradas compartieran valor, la diferencia se perdería en cada
 * fila sin forma de reconstruirla (V16). Los animales en adopción sí las
 * mezclan —la protectora y el administrador usan los dos RETIRADO—; es una
 * asimetría conocida, no un modelo a seguir.
 *
 * Cualquier decisión que dependa del estado se escribe como switch-expresión sin
 * rama por defecto: así un valor nuevo rompe la compilación en cada sitio que
 * tiene que decidir qué hacer con él, en vez de colarse por un default.
 */
public enum EstadoAlerta {
    ACTIVA,
    ENCONTRADA,
    RETIRADA,
    ELIMINADA
}
