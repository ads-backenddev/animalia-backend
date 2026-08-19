package com.rescateanimal.backend.exception;

/**
 * La protectora autenticada no es la dueña del animal que intenta gestionar
 * (RF-07). Se traduce a 403.
 *
 * No es un OperacionNoPermitidaException, que se traduce a 400: aquí la
 * petición está bien formada y el animal existe, y lo que falla es el permiso.
 * Es el mismo caso que ChatAjenoException, con la misma respuesta: el recurso
 * está ahí, pero no es tuyo.
 *
 * Se responde 403 y no 404 porque el catálogo es público entre autenticados —
 * GET /api/adopcion/{id} ya confirma que ese animal existe—, así que ocultarlo
 * aquí no escondería nada y a cambio el cliente distingue "no es tuyo" de "no
 * existe".
 */
public class AnimalAjenoException extends RuntimeException {

    public AnimalAjenoException(String mensaje) {
        super(mensaje);
    }
}
