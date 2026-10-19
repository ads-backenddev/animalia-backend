package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.EstadoAlerta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Detalle de una alerta, con sus avistamientos (GET /api/alertas/{id}).
 *
 * TIENE DOS FORMAS, según el estado:
 * - ACTIVA o ENCONTRADA: todo, avistamientos VISIBLE incluidos.
 * - RETIRADA o ELIMINADA: solo id, especie, estado y createdAt; el resto llega
 *   a null y avistamientos vacío. Responde 200 igualmente, para que el cliente
 *   distinga "esta alerta terminó" de "esta alerta nunca existió" (404); pero
 *   no vuelve a publicar lo que su dueño o el administrador retiraron.
 *
 * Del autor y de los autores de los avistamientos solo viajan los datos
 * públicos de AutorResponse: nunca email ni teléfono (RNF-10).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertaDetalleResponse {

    private Long id;
    private String descripcion;
    private String fotoUrl;
    private String especie;
    private Instant fechaAvistamiento;
    private UbicacionResponse ubicacion;
    private Integer radioBusqueda;
    private EstadoAlerta estado;
    private Instant createdAt;
    private AutorResponse autor;
    private List<AvistamientoResponse> avistamientos;
}
