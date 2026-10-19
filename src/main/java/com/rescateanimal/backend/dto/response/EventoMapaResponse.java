package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mensaje que viaja por el canal WebSocket /topic/mapa (RF-04).
 *
 * Lleva la acción además de la alerta para que el cliente sepa si debe añadir un
 * marcador, moverlo o quitarlo, sin tener que recargar el mapa entero.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventoMapaResponse {

    public enum Accion {
        /** Alerta recién publicada. */
        ALTA,
        /** Cambio en los datos de una alerta ya visible. */
        ACTUALIZACION,
        /** La mascota apareció: el marcador debe retirarse del mapa (RF-14). */
        CIERRE
    }

    private Accion accion;
    private AlertaMapaResponse alerta;
}
