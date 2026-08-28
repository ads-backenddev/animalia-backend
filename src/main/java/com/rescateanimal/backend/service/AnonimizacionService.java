package com.rescateanimal.backend.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cómo se convierte una cuenta en una cuenta sin dueño (RF-19).
 *
 * VIVE APARTE PORQUE LA USAN DOS LADOS. UsuarioService la aplica al borrar, y
 * AuthService la consulta al registrar para saber si un email pertenece a una
 * cuenta ya eliminada. Si cada uno construyera el email anonimizado por su
 * cuenta, el día que cambiara el formato dejarían de reconocerse entre ellos y
 * los emails de las cuentas borradas volverían a quedar libres sin que nada
 * fallara: la clase de error que no se ve hasta que alguien se registra con el
 * email de otro.
 *
 * EL EMAIL SE SUSTITUYE POR UN HASH SUYO, y esa es la decisión de fondo del
 * borrado. Hay dos exigencias que tiran en direcciones opuestas:
 *
 * - Borrar de verdad. El email es dato personal y RF-19 pide que desaparezca;
 *   conservarlo "por si acaso" convertiría el borrado en un archivado.
 * - Que el email no quede libre. Si al borrar se liberase, cualquiera podría
 *   registrarse con el email de quien se fue y aparecer, ante la otra parte de
 *   cada conversación, como la misma persona con la que habló. El histórico se
 *   conserva precisamente para que esas conversaciones sigan teniendo sentido, y
 *   eso lo estropearía.
 *
 * Un hash resuelve las dos: no se puede volver del hash al email —así que el
 * dato original desaparece— pero sí se puede comprobar si un email candidato da
 * ese hash, que es todo lo que hace falta para rechazar el registro. La dirección
 * resultante ocupa el mismo índice único que cualquier otra, así que no hace
 * falta ni una columna nueva ni una migración.
 *
 * EL DOMINIO ES .invalid A PROPÓSITO. Lo reserva el RFC 2606 para que nunca
 * exista: ningún registrador puede venderlo y ningún correo enviado ahí llega a
 * ninguna parte. Si algún día un proceso intentara escribir a la dirección de una
 * cuenta borrada, fallaría en el acto en vez de mandarle un correo a alguien.
 *
 * LO QUE ESTO NO ES, y conviene decirlo: no es anonimato perfecto. Quien tenga
 * acceso a la base de datos y ya sospeche el email de una persona puede calcular
 * el hash y confirmar si tuvo cuenta. Es la contrapartida inevitable de poder
 * reconocer el email al registrarse; un hash con pimienta lo cerraría, pero
 * entonces perder esa pimienta liberaría en silencio todos los emails borrados,
 * que es un fallo peor porque no se nota. Queda anotado en docs/api-rest.md §19.
 */
@Service
public class AnonimizacionService {

    /** Dominio reservado por el RFC 2606: no existe ni puede existir. */
    static final String DOMINIO_INEXISTENTE = "@cuenta-eliminada.invalid";

    /** Lo que ve la otra parte de una conversación donde alguien se dio de baja. */
    static final String NOMBRE_ANONIMO = "Usuario eliminado";

    /**
     * El email con el que queda una cuenta borrada: irreversible y siempre el
     * mismo para el mismo email de partida.
     *
     * Determinista a propósito: es lo que permite a AuthService reconocerlo al
     * registrar. Y se normaliza igual que en el registro —recortado y en
     * minúsculas— porque si no, "Ana@Test.es" y "ana@test.es" darían hashes
     * distintos y uno de los dos volvería a quedar libre.
     */
    public String emailAnonimizado(String emailOriginal) {
        return sha256(normalizar(emailOriginal)) + DOMINIO_INEXISTENTE;
    }

    /** El nombre que sustituye al real. Igual para todos: no distingue a nadie. */
    public String nombreAnonimo() {
        return NOMBRE_ANONIMO;
    }

    /**
     * Un valor para password_hash con el que ningún login puede acertar jamás.
     *
     * NO ES UNA CONTRASEÑA ALEATORIA, y la diferencia importa: una aleatoria es
     * improbable de acertar, esta es IMPOSIBLE. BCrypt solo valida cadenas con su
     * formato —$2a$coste$22 caracteres de sal y 31 de hash—, y esta no lo tiene,
     * así que matches() devuelve false para cualquier entrada sin llegar a
     * comparar nada. La columna es NOT NULL, de ahí que haya que poner algo en
     * vez de dejarla vacía.
     */
    public String hashInutilizable() {
        return "CUENTA-ELIMINADA-SIN-CREDENCIAL";
    }

    /** Si un email es el de una cuenta ya borrada, sin necesidad de conocerlo. */
    public boolean correspondeACuentaEliminada(String emailCandidato, String emailGuardado) {
        return emailAnonimizado(emailCandidato).equals(emailGuardado);
    }

    // ---------------------------------------------------------------------

    private static String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String sha256(String texto) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en toda JVM; si falta, el entorno está roto.
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }
}
