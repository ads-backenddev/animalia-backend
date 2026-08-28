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
     * NADIE LA RELLENA, Y YA NO VA A HACERLO: el campo está muerto. Este DTO
     * solo se construye al registrar una protectora, y una que acaba de nacer no
     * tiene valoraciones por definición. Antes se copiaba de
     * PROTECTORA.valoracion_media, una columna derivada que nadie mantenía y que
     * era nula siempre; se eliminó en la migración V9 y la media se calcula donde
     * de verdad se lee (ValoracionService).
     *
     * Se conservaba a la espera del directorio de RF-06, que estaba diseñado
     * sobre este mismo DTO. Al construirlo resultó que no servía —arrastra dentro
     * un UsuarioResponse entero, con rol, idioma y estado de la cuenta, que un
     * listado público no tiene por qué enseñar— y el directorio se quedó con
     * ProtectoraDirectorioResponse y ProtectoraDetalleResponse, que enumeran
     * campo a campo lo que exponen. Así que este ya no tiene ningún futuro uso
     * previsto y es candidato a desaparecer; queda anotado en docs/api-rest.md
     * §20. No se ha quitado en este bloque porque cambia un DTO del contrato de
     * RF-01 y eso se aprueba aparte, aunque el JSON no cambiaría: al ser NON_NULL
     * y valer siempre null, el campo no se serializa nunca.
     *
     * El NON_NULL es lo que hace que "no hay media" se diga igual aquí, en el
     * panel, en el detalle de un animal y en el directorio: omitiendo el campo,
     * no mandando null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal valoracionMedia;
    private Instant createdAt;

    /** Datos de la cuenta de acceso asociada. */
    private UsuarioResponse usuario;
}
