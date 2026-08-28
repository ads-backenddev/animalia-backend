package com.rescateanimal.backend.service;

import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve el par (TipoContenido, id) contra la tabla que le corresponde.
 *
 * POR QUÉ EXISTE. En esta API una "publicación" no es una tabla sino tres
 * —ALERTA, AVISTAMIENTO y ANIMAL_ADOPCION—, y el id solo es único dentro de cada
 * una: el 7 existe a la vez como alerta, como avistamiento y como animal. Por eso
 * todo lo que apunta a una publicación guarda el par (tipo, id): lo hace
 * MATCH_REGISTRO, lo hace REPORTE y lo recibe el borrado del administrador. Cada
 * sitio que quiera saber "¿a qué apunta esto?" necesita el mismo reparto, y
 * tenerlo escrito en varios sitios es cómo se acaba con dos que no coinciden.
 *
 * EL SWITCH NO LLEVA RAMA POR DEFECTO, y esa ausencia es la pieza importante. Al
 * ser una expresión sobre un enum, el compilador exige que estén los tres casos:
 * el día que aparezca un cuarto tipo de publicación, esto deja de compilar y
 * señala el sitio exacto. Con un `default` —o con un if/else— el tipo nuevo se
 * colaría en ejecución por la rama de descarte y nadie se enteraría hasta que un
 * reporte apuntara a la nada. Es la misma lección que dejó el switch de
 * AdminService.retirarPublicacion, que parecía exhaustivo y no lo era por ser una
 * sentencia en lugar de una expresión.
 *
 * NO ABSORBE LA RETIRADA DE AdminService, y no por descuido: aquel decide además
 * qué estado terminal aplicar a cada tabla —ELIMINADA, ELIMINADO, RETIRADO— y
 * escribe una notificación distinta según el caso. Son dos preguntas distintas
 * sobre el mismo reparto: aquí "¿existe?", allí "retírala". Lo que no está
 * duplicado es la lógica; lo que se comparte es la forma del reparto, y esta
 * clase es el sitio al que mirar cuando se añada un tipo.
 */
@Service
@RequiredArgsConstructor
public class PublicacionService {

    private final AlertaRepository alertaRepository;
    private final AvistamientoRepository avistamientoRepository;
    private final AnimalAdopcionRepository animalAdopcionRepository;

    /**
     * Si la publicación existe en la tabla que le corresponde según su tipo.
     *
     * NO MIRA EL ESTADO. Una alerta ELIMINADA o un animal RETIRADO siguen
     * existiendo, y poder reportarlos es correcto: alguien pudo verlos antes de
     * que se retiraran, y el administrador necesita el reporte para juzgar a
     * quien los publicó aunque el contenido ya no esté visible.
     */
    @Transactional(readOnly = true)
    public boolean existe(TipoContenido tipo, Long publicacionId) {
        return switch (tipo) {
            case ALERTA -> alertaRepository.existsById(publicacionId);
            case AVISTAMIENTO -> avistamientoRepository.existsById(publicacionId);
            case ADOPCION -> animalAdopcionRepository.existsById(publicacionId);
        };
    }

    /**
     * Igual que {@link #existe}, pero falla con 404 en vez de devolver false.
     *
     * El mensaje nombra el tipo además del id porque sin él sería engañoso: "la
     * publicación 7 no existe" es falso si el 7 existe como alerta y se buscaba
     * como avistamiento.
     */
    @Transactional(readOnly = true)
    public void exigirQueExista(TipoContenido tipo, Long publicacionId) {
        if (!existe(tipo, publicacionId)) {
            throw new ResourceNotFoundException(
                    "La publicación " + publicacionId + " de tipo " + tipo + " no existe");
        }
    }
}
