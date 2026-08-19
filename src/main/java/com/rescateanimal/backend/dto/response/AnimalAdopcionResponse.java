package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.Tamanyo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un animal del catálogo de adopción (RF-07, RF-22).
 *
 * Es la salida del catálogo paginado y de las operaciones de la protectora
 * sobre sus animales. Lleva ya el nombre de la protectora porque el catálogo lo
 * pinta en cada tarjeta: sin él, el cliente tendría que pedir el detalle de
 * cada fila solo para rellenar esa línea.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalAdopcionResponse {

    private Long id;
    private String nombre;
    private String especie;
    private Tamanyo tamanyo;
    private String descripcion;
    private String fotoUrl;
    private String zonaGeografica;

    /** DISPONIBLE o ADOPTADO (RF-20). */
    private String estado;

    private Long protectoraId;
    private String protectoraNombre;

    private LocalDateTime createdAt;
}
