package com.rescateanimal.backend.service;

import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Convierte la paginación que pide el cliente en la que llega a los
 * repositorios, con los mismos topes en todos los listados.
 *
 * EL NÚMERO DE PÁGINA TIENE TECHO, y pasarlo es un 400. Sin él,
 * ?page=2147483647 llevaba a Spring Data un desplazamiento que no cabe en un
 * int («Page offset exceeds Integer.MAX_VALUE») y la petición acababa en el 500
 * genérico, con una traza de error en el log por cada intento (informe ENS,
 * C16). PAGINA_MAXIMA con el tamaño máximo de 100 deja el desplazamiento en un
 * millón de filas, muy lejos de ese límite; y ningún listado de la plataforma
 * tiene tantas páginas: con el tamaño por defecto serían doscientos mil
 * elementos. Más allá, el OFFSET obligaría además a PostgreSQL a leer y
 * descartar todas las filas anteriores.
 *
 * El tamaño, en cambio, se ajusta en silencio a [1, máximo], como hasta ahora:
 * pedir más de lo que se sirve no es un error, y la respuesta dice el tamaño
 * aplicado. Una página negativa ya llega como 0 desde Spring.
 */
final class Paginas {

    /** Última página que se sirve; las páginas empiezan en 0. */
    static final int PAGINA_MAXIMA = 10_000;

    private Paginas() {
    }

    /** La página pedida, sin orden: lo fija la consulta. */
    static PageRequest acotada(Pageable pedida, int maximoPorPagina) {
        return acotada(pedida, maximoPorPagina, Sort.unsorted());
    }

    /** La página pedida, en el orden que fija el servicio y no el cliente. */
    static PageRequest acotada(Pageable pedida, int maximoPorPagina, Sort orden) {
        if (pedida.getPageNumber() > PAGINA_MAXIMA) {
            throw new ParametrosInvalidosException("El parámetro page no puede pasar de " + PAGINA_MAXIMA);
        }
        return PageRequest.of(
                Math.max(0, pedida.getPageNumber()),
                Math.min(Math.max(1, pedida.getPageSize()), maximoPorPagina),
                orden);
    }
}
