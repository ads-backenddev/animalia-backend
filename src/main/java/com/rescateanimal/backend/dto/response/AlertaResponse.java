package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.EstadoAlerta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Vista completa de una alerta de mascota perdida (RF-03). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertaResponse {

    private Long id;
    private String descripcion;
    private String fotoUrl;
    private String especie;
    private LocalDateTime fechaAvistamiento;
    private UbicacionResponse ubicacion;
    private Integer radioBusqueda;
    private EstadoAlerta estado;
    private LocalDateTime createdAt;
    private AutorResponse autor;
}
