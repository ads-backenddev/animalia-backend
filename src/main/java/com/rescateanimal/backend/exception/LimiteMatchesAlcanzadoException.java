package com.rescateanimal.backend.exception;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * El usuario ha agotado su cuota diaria de matches de adopción (RF-24). Se
 * traduce a 429. Los matches sobre avistamientos no consumen cuota.
 *
 * Lleva el límite y el momento de reinicio porque el requisito no se conforma
 * con bloquear: pide decirle al usuario que podrá continuar al día siguiente,
 * y el cliente necesita ese dato para mostrarlo sin inventárselo.
 */
@Getter
public class LimiteMatchesAlcanzadoException extends RuntimeException {

    private final int limiteDiario;
    private final LocalDateTime reiniciaEn;

    public LimiteMatchesAlcanzadoException(int limiteDiario, LocalDateTime reiniciaEn) {
        super("Has alcanzado el límite de " + limiteDiario
                + " matches de adopción diarios. Podrás continuar mañana.");
        this.limiteDiario = limiteDiario;
        this.reiniciaEn = reiniciaEn;
    }
}
