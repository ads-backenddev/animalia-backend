package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Vista completa de un avistamiento (RF-21). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvistamientoResponse {

    private Long id;
    private Long alertaId;
    private String descripcion;
    private String fotoUrl;
    private UbicacionResponse ubicacion;
    private LocalDateTime createdAt;
    private AutorResponse autor;
}
