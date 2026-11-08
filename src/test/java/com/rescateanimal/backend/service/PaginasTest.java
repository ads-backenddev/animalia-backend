package com.rescateanimal.backend.service;

import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los topes comunes de los listados. Que cada listado los aplica, y que el
 * cliente recibe un 400 y no un 500, lo comprueba PaginaMaximaTest contra el
 * servidor.
 */
class PaginasTest {

    private static final Sort ORDEN = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Test
    @DisplayName("La página máxima se sirve, con el orden que fija el servicio")
    void laPaginaMaximaSeSirve() {
        PageRequest peticion = Paginas.acotada(PageRequest.of(Paginas.PAGINA_MAXIMA, 20), 100, ORDEN);

        assertThat(peticion.getPageNumber()).isEqualTo(Paginas.PAGINA_MAXIMA);
        assertThat(peticion.getPageSize()).isEqualTo(20);
        assertThat(peticion.getSort()).isEqualTo(ORDEN);
    }

    @Test
    @DisplayName("Una página más es un 400, también la que antes desbordaba el desplazamiento")
    void porEncimaDeLaMaximaSeRechaza() {
        assertThatThrownBy(() -> Paginas.acotada(PageRequest.of(Paginas.PAGINA_MAXIMA + 1, 20), 100))
                .isInstanceOf(ParametrosInvalidosException.class)
                .hasMessageContaining(String.valueOf(Paginas.PAGINA_MAXIMA));
        assertThatThrownBy(() -> Paginas.acotada(PageRequest.of(Integer.MAX_VALUE, 20), 100))
                .isInstanceOf(ParametrosInvalidosException.class);
    }

    @Test
    @DisplayName("El tamaño se ajusta a [1, máximo] sin error, y sin orden queda sin orden")
    void elTamanyoSeAjusta() {
        assertThat(Paginas.acotada(PageRequest.of(0, 500), 100).getPageSize()).isEqualTo(100);
        assertThat(Paginas.acotada(PageRequest.of(3, 100), 50).getPageSize()).isEqualTo(50);
        assertThat(Paginas.acotada(PageRequest.of(0, 20), 100).getSort().isUnsorted()).isTrue();
    }
}
