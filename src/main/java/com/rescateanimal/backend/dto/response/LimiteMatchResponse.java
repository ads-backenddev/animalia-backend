package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Estado del límite diario de matches (RF-24), para que el cliente lo muestre
 * antes de agotarlo en lugar de descubrirlo con un 429.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LimiteMatchResponse {

    private Integer limiteDiario;
    private Integer consumidos;
    private Integer restantes;

    /**
     * Medianoche del día siguiente: cuando el contador vuelve a cero.
     *
     * Es el único campo de fecha-hora de la API que NO es un Instant, y es
     * deliberado. La cuota de RF-24 se cuenta por día natural
     * —LIMITE_MATCH_DIARIO.fecha es un DATE— y "mañana a las 00:00" es una
     * fecha de calendario, no un punto en la línea del tiempo.
     *
     * El día es el de Europe/Madrid, fijado en MatchService.ZONA_CUOTA, así que
     * estas 00:00 son medianoche peninsular en cualquier entorno. Es una
     * simplificación consciente para el alcance del proyecto y no es correcta
     * para Canarias; el detalle está en MatchService.consumirCuotaDiaria.
     */
    private LocalDateTime reiniciaEn;
}
