package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Una valoración de 1 a 5 estrellas recibida por una protectora (RF-23).
 *
 * El autor va como AutorResponse, sin email ni teléfono, por el mismo motivo que
 * en el resto de listados: son datos personales y RNF-10 no los admite aquí.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValoracionResponse {

    private Long id;

    /** Quién valoró. */
    private AutorResponse autor;

    /** De 1 a 5, como impone la entidad Valoracion. */
    private Integer puntuacion;

    private Instant createdAt;
}
