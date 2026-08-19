package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.Tamanyo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Detalle de un animal en adopción, con los datos de la protectora que lo
 * publica (RF-07, RF-10).
 *
 * Los datos de la protectora van embebidos y aplanados, como en
 * ChatDetalleResponse: son los que se leen antes de decidir si contactar, y
 * pedirlos aparte obligaría a una segunda llamada para pintar una sola pantalla.
 *
 * Deliberadamente NO se reutiliza ProtectoraResponse, que arrastra dentro un
 * UsuarioResponse con email y teléfono. Aquí solo viaja lo público de la
 * organización (RNF-10); para hablar con ella está
 * POST /api/protectoras/{id}/contactar, que no expone sus datos de contacto.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalAdopcionDetalleResponse {

    private Long id;
    private String nombre;
    private String especie;
    private Tamanyo tamanyo;
    private String descripcion;
    private String fotoUrl;
    private String zonaGeografica;
    private String estado;
    private LocalDateTime createdAt;

    private Long protectoraId;
    private String protectoraNombre;
    private String protectoraDireccion;
    private String protectoraZonaGeografica;

    /** Media de 1 a 5 estrellas; nula mientras nadie la haya valorado (RF-23). */
    private BigDecimal protectoraValoracionMedia;
}
