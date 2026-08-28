package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.ValoracionResponse;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.exception.SinInteraccionPreviaException;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.Valoracion;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.repository.ValoracionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Valoraciones de 1 a 5 estrellas sobre una protectora (RF-23).
 *
 * DOS RESPONSABILIDADES, y conviene separarlas porque tienen reglas distintas:
 * emitir una valoración, que exige haber tratado con la protectora, y leer la
 * media, que no exige nada porque es información pública.
 *
 * VALORAR NO ES LIBRE. RF-23 pide una valoración "tras una adopción o contacto",
 * y la diferencia no es de matiz: sin esa condición cualquiera puede puntuar a
 * cualquiera sin haber tratado con ella, y un puñado de cuentas nuevas bastaría
 * para hundir la reputación de una protectora o inflar la propia. Lo que se
 * exige es un contacto real —ver MatchRegistroRepository.existeContactoConProtectora—
 * y quien no lo tenga recibe un 403, no un 400: la petición es correcta, lo que
 * falta es el derecho a hacerla.
 *
 * VOLVER A VALORAR ES CAMBIAR DE OPINIÓN, no votar dos veces, así que el endpoint
 * es un PUT idempotente: si ya hay valoración, se actualiza la puntuación de la
 * fila existente. La restricción UNIQUE de la migración V9 respalda esa intención
 * para el caso que este código no puede cubrir por sí solo, que es el de dos
 * peticiones simultáneas del mismo usuario.
 *
 * LA MEDIA NO SE GUARDA EN NINGUNA PARTE. PROTECTORA tenía una columna
 * valoracion_media que nadie mantenía —nula para todas, indefinidamente— y se
 * eliminó en V9. Aquí se calcula con AVG y COUNT al leerla, de modo que no puede
 * quedar desfasada. Es el mismo criterio que ya seguía el panel de RF-10 por su
 * cuenta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValoracionService {

    /**
     * Dos decimales, que es la precisión que tenía la columna eliminada y la que
     * expone el resto de la API. Redondeo HALF_UP: 4.125 sube a 4.13, que es lo
     * que cualquiera espera al ver una nota.
     */
    private static final int DECIMALES = 2;

    private final ValoracionRepository valoracionRepository;
    private final ProtectoraRepository protectoraRepository;
    private final UsuarioRepository usuarioRepository;
    private final MatchRegistroRepository matchRegistroRepository;

    /**
     * Media y número de valoraciones de una protectora.
     *
     * @param media nula cuando no hay ninguna valoración. No es cero: sin datos
     *              la media no existe, y un cero se pintaría como "pésima".
     */
    public record ResumenValoraciones(BigDecimal media, long total) {
    }

    // ---------------------------------------------------------------------
    // Emitir (RF-23)
    // ---------------------------------------------------------------------

    /**
     * Crea o actualiza la valoración de un usuario sobre una protectora.
     *
     * El orden de las comprobaciones no es indiferente: primero que la
     * protectora exista (404), después que quien valora haya tratado con ella
     * (403). Al revés, un 403 sobre una protectora inexistente diría "no puedes
     * valorarla" en lugar de "no existe", y eso confunde a quien depura y no
     * protege nada, porque el directorio de protectoras es público.
     */
    @Transactional
    public ValoracionResponse valorar(Long protectoraId, Long usuarioId, Integer puntuacion) {
        Protectora protectora = protectoraRepository.findById(protectoraId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La protectora " + protectoraId + " no existe"));

        exigirContactoPrevio(usuarioId, protectoraId);

        Optional<Valoracion> existente =
                valoracionRepository.findByUsuarioIdAndProtectoraId(usuarioId, protectoraId);

        Valoracion valoracion = existente.orElseGet(() -> nueva(protectora, usuarioId));
        valoracion.setPuntuacion(puntuacion);

        Valoracion guardada = valoracionRepository.save(valoracion);
        log.debug("El usuario {} ha valorado con {} a la protectora {}",
                usuarioId, puntuacion, protectoraId);

        return aResponse(guardada);
    }

    /**
     * Exige haber contactado antes con la protectora.
     *
     * NO SE COMPRUEBA QUE LA PROTECTORA ESTÉ ACTIVA, y es deliberado: una
     * protectora que se desverifica después no borra la experiencia de quien
     * trató con ella, y su reputación pasada sigue siendo información legítima.
     * Lo que sí impide el contacto previo es valorar a una que nunca llegó a
     * publicar nada, porque entonces no puede haber match sobre ninguno de sus
     * animales.
     */
    private void exigirContactoPrevio(Long usuarioId, Long protectoraId) {
        boolean haContactado = matchRegistroRepository.existeContactoConProtectora(
                usuarioId, protectoraId, TipoPublicacion.ADOPCION);

        if (!haContactado) {
            log.debug("El usuario {} ha intentado valorar la protectora {} sin haber contactado",
                    usuarioId, protectoraId);
            throw new SinInteraccionPreviaException(
                    "Solo puedes valorar una protectora con la que hayas contactado");
        }
    }

    private Valoracion nueva(Protectora protectora, Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El usuario " + usuarioId + " no existe"));

        Valoracion valoracion = new Valoracion();
        valoracion.setUsuario(usuario);
        valoracion.setProtectora(protectora);
        return valoracion;
    }

    // ---------------------------------------------------------------------
    // Leer (RF-10, RF-23)
    // ---------------------------------------------------------------------

    /**
     * Media y total de una protectora, calculados sobre las valoraciones reales.
     *
     * Dos agregaciones en vez de traerse las filas: quien llama solo quiere el
     * número, y en el detalle de un animal cargar todas las valoraciones de su
     * protectora para promediarlas sería trabajo tirado.
     */
    @Transactional(readOnly = true)
    public ResumenValoraciones resumenDe(Long protectoraId) {
        Double media = valoracionRepository.mediaDeProtectora(protectoraId);
        long total = valoracionRepository.countByProtectoraId(protectoraId);

        return new ResumenValoraciones(redondear(media), total);
    }

    /**
     * Convierte la media que devuelve AVG a la precisión que expone la API.
     *
     * Un null entra y sale como null: la protectora sin valoraciones no tiene
     * media, y devolver BigDecimal.ZERO sería afirmar que la tiene y que es la
     * peor posible.
     */
    public static BigDecimal redondear(Double media) {
        if (media == null || media.isNaN()) {
            return null;
        }
        return BigDecimal.valueOf(media).setScale(DECIMALES, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------------

    private static ValoracionResponse aResponse(Valoracion valoracion) {
        Usuario autor = valoracion.getUsuario();
        return ValoracionResponse.builder()
                .id(valoracion.getId())
                .autor(AutorResponse.builder()
                        .id(autor.getId())
                        .nombre(autor.getNombre())
                        .fotoUrl(autor.getFotoUrl())
                        .build())
                .puntuacion(valoracion.getPuntuacion())
                .createdAt(valoracion.getCreatedAt())
                .build();
    }
}
