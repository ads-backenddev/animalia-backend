package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Datos públicos de quien publica una alerta o un avistamiento.
 *
 * Deliberadamente reducido: no incluye email ni teléfono, que son datos
 * personales y no deben viajar en un listado público (RNF-10).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutorResponse {

    private Long id;
    private String nombre;
    private String fotoUrl;
}
