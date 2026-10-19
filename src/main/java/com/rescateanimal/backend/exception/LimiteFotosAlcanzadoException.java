package com.rescateanimal.backend.exception;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * El usuario ha agotado sus subidas de fotos del día. Se traduce a 429, con el
 * límite y la hora de reinicio, igual que LimiteMatchesAlcanzadoException.
 */
@Getter
public class LimiteFotosAlcanzadoException extends RuntimeException {

    private final int limiteDiario;
    private final LocalDateTime reiniciaEn;

    public LimiteFotosAlcanzadoException(int limiteDiario, LocalDateTime reiniciaEn) {
        super("Has alcanzado el límite de " + limiteDiario
                + " fotos subidas hoy. Podrás subir más mañana.");
        this.limiteDiario = limiteDiario;
        this.reiniciaEn = reiniciaEn;
    }
}
