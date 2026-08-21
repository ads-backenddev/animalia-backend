package com.rescateanimal.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Contadores de la pantalla principal del panel de la protectora (RF-10).
 *
 * Existe para que el panel web no tenga que pedir los tres listados completos
 * solo para pintar unas cifras: se resuelve con consultas de agregación, sin
 * traerse ni un animal ni una solicitud a memoria.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumenPanelResponse {

    /** Animales DISPONIBLE: los que están ofreciéndose ahora mismo en el catálogo. */
    private long animalesDisponibles;

    /** Animales ADOPTADO: adopciones completadas y confirmadas (RF-20). */
    private long animalesAdoptados;

    /** Animales RETIRADO: sacados del catálogo sin adoptar. */
    private long animalesRetirados;

    /** Suma de los tres anteriores: todo lo que la protectora ha publicado alguna vez. */
    private long animalesTotales;

    /**
     * Solicitudes que la protectora todavía tiene que atender.
     *
     * QUÉ CUENTA COMO PENDIENTE. RF-10 no lo define y MATCH_REGISTRO no tiene
     * ningún estado que lo diga, así que hay que elegir un criterio: aquí una
     * solicitud está pendiente mientras su animal siga DISPONIBLE. En cuanto el
     * animal pasa a ADOPTADO o a RETIRADO la solicitud deja de requerir gestión
     * —el caso está cerrado, con adopción o sin ella— aunque su chat siga vivo.
     *
     * El criterio alternativo sería "sin responder en el chat", que mide otra
     * cosa: la diligencia de la protectora contestando, no cuántos casos tiene
     * abiertos. Se ha elegido el primero porque es el que corresponde a un
     * contador de gestión, y porque no depende de MENSAJE.
     */
    private long solicitudesPendientes;

    /** Todas las solicitudes recibidas, en cualquier estado del animal. */
    private long solicitudesTotales;

    /**
     * Media de valoraciones, o nula si aún no tiene ninguna.
     *
     * Se omite del JSON cuando falta, en vez de viajar como cero: cero sería una
     * valoración pésima y lo que ocurre es que no hay ninguna. Hoy es siempre
     * nula porque nada puede crear valoraciones todavía; ver
     * ValoracionesPanelResponse.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal valoracionMedia;

    /** Cuántas valoraciones sostienen esa media. */
    private long totalValoraciones;
}
