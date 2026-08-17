package com.rescateanimal.backend.model.enums;

/**
 * Tipo de publicación dentro de la plataforma, considerando las tres tablas que
 * contienen contenido publicable: ALERTA, AVISTAMIENTO y ANIMAL_ADOPCION.
 *
 * Existe porque el id de una publicación solo es único dentro de su propia tabla,
 * así que hace falta un discriminador para identificarla sin ambigüedad en:
 * - el borrado de publicaciones por el administrador (RF-11),
 * - los reportes cuyo tipoObjetivo es PUBLICACION (RF-26).
 *
 * No se reutiliza TipoPublicacion porque ese enum solo admite los dos tipos
 * sobre los que se puede hacer match (RF-08, RF-09), donde ALERTA no es un valor
 * válido.
 */
public enum TipoContenido {
    ALERTA,
    AVISTAMIENTO,
    ADOPCION
}
