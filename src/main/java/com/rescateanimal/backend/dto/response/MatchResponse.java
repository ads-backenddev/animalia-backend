package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Resultado de un deslizamiento (RF-08, RF-15).
 *
 * chatId solo viene relleno cuando interesado es true: un descarte no abre
 * conversación, y el campo se omite del JSON en lugar de viajar a null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchResponse {

    private Long id;
    private Long publicacionId;
    private TipoPublicacion tipoPublicacion;
    private Boolean interesado;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long chatId;

    private LocalDateTime createdAt;
}
