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

    /** Medianoche del día siguiente: cuando el contador vuelve a cero. */
    private LocalDateTime reiniciaEn;
}
