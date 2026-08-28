package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vista de una protectora. Tras el registro se devuelve con estado PENDIENTE,
 * a la espera de la verificación del administrador (RF-01, RF-11).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProtectoraResponse {

    private Long id;
    private String nombreOrganizacion;
    private String direccion;
    private String zonaGeografica;
    private EstadoProtectora estado;
    /**
     * Media de 1 a 5 estrellas, omitida del JSON cuando no la hay (RF-23).
     *
     * HOY NADIE LA RELLENA, y conviene saberlo: este DTO solo se construye al
     * registrar una protectora, y una que acaba de nacer no tiene valoraciones
     * por definición. Antes se copiaba de PROTECTORA.valoracion_media, una
     * columna derivada que nadie mantenía y que era nula siempre; se eliminó en
     * la migración V9 y la media se calcula donde de verdad se lee
     * (ValoracionService). El campo se conserva porque este DTO es también el del
     * listado y el detalle del directorio de RF-06, que está diseñado y sin
     * construir: ahí sí tendrá valor, y lo tomará del service.
     *
     * El NON_NULL es lo que hace que "no hay media" se diga igual aquí, en el
     * panel y en el detalle de un animal: omitiendo el campo, no mandando null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal valoracionMedia;
    private Instant createdAt;

    /** Datos de la cuenta de acceso asociada. */
    private UsuarioResponse usuario;
}
