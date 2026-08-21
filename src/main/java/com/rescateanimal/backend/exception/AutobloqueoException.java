package com.rescateanimal.backend.exception;

/**
 * Un administrador intenta bloquear su propia cuenta (RF-11). Se traduce a 403.
 *
 * No es un 400: la petición está bien formada, el usuario existe y quien la hace
 * tiene el rol necesario para bloquear cuentas. Lo que falla es el permiso sobre
 * ESE objetivo en concreto, igual que en PublicacionPropiaException, y por un
 * motivo más serio que la simetría: un bloqueo impide iniciar sesión, así que un
 * administrador que se bloqueara perdería el acceso al panel y con él la
 * capacidad de desbloquearse. Solo saldría de ahí tocando la base de datos a
 * mano.
 *
 * No reutiliza CuentaNoAutorizadaException porque ese 403 significa "tu cuenta no
 * puede operar", y aquí la del administrador opera perfectamente; lo que se
 * rechaza es la operación, no la cuenta.
 */
public class AutobloqueoException extends RuntimeException {

    public AutobloqueoException(String mensaje) {
        super(mensaje);
    }
}
