package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.EstadoAlerta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Alerta reducida a lo que necesita pintar un marcador en el mapa (RF-04).
 *
 * Se envía por el listado /api/alertas/mapa y por el canal /topic/mapa. Es más
 * ligera que AlertaResponse a propósito: el mapa puede mostrar cientos de
 * marcadores y debe soportar 100 usuarios simultáneos (RNF-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertaMapaResponse {

    private Long id;
    private String especie;
    private String fotoUrl;
    private UbicacionResponse ubicacion;
    private Instant fechaAvistamiento;
    private EstadoAlerta estado;
}
