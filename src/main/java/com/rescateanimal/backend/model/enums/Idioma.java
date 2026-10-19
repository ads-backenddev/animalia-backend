package com.rescateanimal.backend.model.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * Idiomas que la aplicación admite (RF-18).
 *
 * SON LAS LENGUAS OFICIALES DE ESPAÑA MÁS EL INGLÉS, que es el ámbito de la
 * plataforma: castellano, catalán, gallego y euskera, y el inglés para quien
 * viva aquí sin hablar ninguna. La lista no está abierta a propósito — ver abajo.
 *
 * POR QUÉ ESTE ENUM NO ES EL TIPO DE LA COLUMNA. USUARIO.idioma sigue siendo un
 * varchar y la entidad sigue guardando un String, en contra de lo que hace el
 * resto de enums del modelo. El motivo es el valor que viaja: son códigos
 * ISO 639-1, que se escriben en minúscula —"es", "ca"—, y esos códigos ya están
 * en el contrato de la API y en las filas existentes. Un @Enumerated(STRING)
 * escribiría "ES" y "CA", así que habría que elegir entre romper el contrato o
 * añadir un convertidor para deshacer lo que el enum acababa de hacer. Este enum
 * es la lista de lo admitido y el sitio donde validar; la columna guarda el
 * código.
 *
 * QUIÉN LO HACE CUMPLIR, en tres capas y por motivos distintos: la validación
 * del DTO da un 400 con el campo señalado antes de tocar nada; este enum es la
 * única lista, de modo que añadir un idioma es tocar un sitio; y el CHECK de la
 * migración V11 es lo último que queda en pie si alguien escribe por SQL. Antes
 * de V11 la columna admitía cualquier cadena de hasta diez caracteres, así que
 * "klingon" era un idioma perfectamente válido para la base de datos.
 */
public enum Idioma {

    ES("es"),
    EN("en"),
    CA("ca"),
    GL("gl"),
    EU("eu");

    /** El idioma con el que nace una cuenta que no elige ninguno. */
    public static final Idioma POR_DEFECTO = ES;

    private final String codigo;

    Idioma(String codigo) {
        this.codigo = codigo;
    }

    /** Código ISO 639-1 en minúscula, que es lo que viaja por la API y se guarda. */
    public String getCodigo() {
        return codigo;
    }

    /**
     * El idioma correspondiente a un código, si lo hay.
     *
     * Acepta el código en cualquier caja —"ES" y "es" son el mismo idioma— y
     * devuelve vacío para lo que no reconoce, en vez de lanzar: quien llama
     * decide si eso es un 400 de validación o un valor por defecto.
     */
    public static Optional<Idioma> desdeCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        String normalizado = codigo.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(idioma -> idioma.codigo.equals(normalizado))
                .findFirst();
    }

    /** Si el código corresponde a un idioma admitido. Para la validación del DTO. */
    public static boolean esValido(String codigo) {
        return desdeCodigo(codigo).isPresent();
    }

    /** Los códigos admitidos, para mensajes de error y documentación. */
    public static String codigosAdmitidos() {
        return String.join(", ", Arrays.stream(values()).map(Idioma::getCodigo).toList());
    }
}
