package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tarjeta del feed de swipe (RF-15).
 *
 * Lleva los datos de la alerta a la que responde el avistamiento —especie y
 * foto original— porque el usuario decide comparando las dos imágenes: la del
 * animal perdido y la del animal visto (RF-21).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvistamientoFeedResponse {

    private Long id;
    private String descripcion;
    private String fotoUrl;
    private UbicacionResponse ubicacion;
    private LocalDateTime createdAt;
    private AutorResponse autor;

    private Long alertaId;
    private String alertaEspecie;
    private String alertaFotoUrl;
}
