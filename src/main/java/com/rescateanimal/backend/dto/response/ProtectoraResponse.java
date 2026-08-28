package com.rescateanimal.backend.dto.response;

import com.rescateanimal.backend.model.enums.EstadoProtectora;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    // NO HAY CAMPO valoracionMedia, y su ausencia es una decisión. Existió como
    // copia de PROTECTORA.valoracion_media, una columna derivada que nadie
    // mantenía —nula para todas, indefinidamente— y que se eliminó en la
    // migración V9. Después se conservó a la espera del directorio de RF-06, que
    // estaba diseñado sobre este mismo DTO; al construirlo resultó que no servía
    // —arrastra dentro un UsuarioResponse entero, con rol, idioma y estado de la
    // cuenta, que un listado público no tiene por qué enseñar— y el directorio se
    // quedó con ProtectoraDirectorioResponse y ProtectoraDetalleResponse. Sin
    // ningún uso previsto ya, se retiró: un campo que siempre vale null es una
    // promesa que el DTO no cumple. El JSON no cambia, porque era NON_NULL y no
    // llegaba a serializarse nunca. La media se lee donde de verdad existe
    // (ValoracionService).

    private Instant createdAt;

    /** Datos de la cuenta de acceso asociada. */
    private UsuarioResponse usuario;
}
