package com.rescateanimal.backend.exception;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * El usuario ya ha registrado hoy los avistamientos que admite una misma alerta.
 * Se traduce a 429, con el límite y la hora de reinicio, igual que los cupos de
 * matches de adopción (LimiteMatchesAlcanzadoException) y de fotos.
 */
@Getter
public class LimiteAvistamientosAlcanzadoException extends RuntimeException {

    private final int limiteDiario;
    private final LocalDateTime reiniciaEn;

    public LimiteAvistamientosAlcanzadoException(int limiteDiario, LocalDateTime reiniciaEn) {
        super("Has alcanzado el límite de " + limiteDiario
                + " avistamientos diarios en esta alerta. Podrás registrar más mañana.");
        this.limiteDiario = limiteDiario;
        this.reiniciaEn = reiniciaEn;
    }
}
