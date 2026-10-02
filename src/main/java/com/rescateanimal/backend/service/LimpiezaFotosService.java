package com.rescateanimal.backend.service;

import com.rescateanimal.backend.repository.FotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * La limpieza diaria de fotos: lo que queda en la carpeta o en la tabla sin que
 * nadie lo vaya a usar.
 *
 * Recoge tres cosas, y cada una tiene su origen:
 * - FOTOS SIN USO: filas de FOTO a las que no apunta ningún foto_url. El cliente
 *   subió la foto y nunca llegó a publicar —canceló el formulario, se cerró la
 *   aplicación—. Se borran fila y archivo.
 * - ARCHIVOS SIN FILA: el segundo paso de un borrado que falló (FotoService
 *   .retirar), o una subida cuyo INSERT no llegó a confirmarse y cuyo rollback
 *   no alcanzó a borrar el archivo. No se sirven —GET /fotos exige fila—, pero
 *   ocupan el volumen, que se paga.
 * - TEMPORALES: subidas cortadas entre escribir y renombrar.
 *
 * TODO LO QUE TENGA MENOS DE 24 HORAS SE RESPETA. Una subida reciente puede
 * estar a punto de usarse, y un archivo reciente sin fila puede ser una subida
 * cuyo INSERT aún no se ha confirmado.
 *
 * EL DISCO VA ANTES QUE LAS FILAS, y no es indiferente. Esta transacción borra
 * filas y deja los archivos para después del commit; si el recorrido del disco
 * fuera detrás, vería esos archivos ya sin fila y los borraría en el acto, antes
 * del commit. Un rollback devolvería entonces filas sin archivo.
 *
 * LIMITACIÓN ACEPTADA: una foto de más de 24 horas sin usar puede borrarse justo
 * mientras su dueño la está publicando, si la publicación comprueba la foto
 * antes de que esto borre la fila y la guarda después. La publicación quedaría
 * apuntando a una foto que da 404. Exige usar una subida de hace más de un día
 * en el segundo exacto de la limpieza, a las 4:30 de la madrugada.
 *
 * Una sola ejecución a la vez está garantizada porque hay una sola instancia: el
 * volumen de Railway no admite réplicas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LimpiezaFotosService {

    static final Duration ANTIGUEDAD_MINIMA = Duration.ofHours(24);

    private final FotoRepository fotoRepository;
    private final AlmacenFotosService almacen;

    public record Resultado(int fotosSinUso, int archivosSinFila, int temporales) {
    }

    @Scheduled(cron = "${app.fotos.limpieza-cron}", zone = "Europe/Madrid")
    @Transactional
    public Resultado limpiar() {
        Instant limite = Instant.now().minus(ANTIGUEDAD_MINIMA);

        // Primero el disco: ver el comentario de la clase.
        int archivosSinFila = borrarArchivosSinFila(limite);
        int temporales = almacen.borrarTemporalesAnterioresA(limite);

        List<String> sinUso = fotoRepository.sinUsoAnterioresA(limite);
        if (!sinUso.isEmpty()) {
            fotoRepository.deleteAllByIdInBatch(sinUso);
            sinUso.forEach(almacen::borrarTrasConfirmar);
        }

        Resultado resultado = new Resultado(sinUso.size(), archivosSinFila, temporales);
        log.info("Limpieza de fotos: {} sin uso, {} archivos sin fila, {} temporales",
                resultado.fotosSinUso(), resultado.archivosSinFila(), resultado.temporales());
        return resultado;
    }

    private int borrarArchivosSinFila(Instant limite) {
        List<String> antiguos = almacen.nombresAnterioresA(limite);
        if (antiguos.isEmpty()) {
            return 0;
        }
        Set<String> conFila = new HashSet<>(fotoRepository.conFila(antiguos));
        List<String> sinFila = antiguos.stream().filter(nombre -> !conFila.contains(nombre)).toList();
        sinFila.forEach(almacen::borrar);
        return sinFila.size();
    }
}
