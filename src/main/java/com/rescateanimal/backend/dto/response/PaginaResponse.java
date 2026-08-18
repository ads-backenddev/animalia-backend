package com.rescateanimal.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Envoltorio de los listados paginados, con la forma que fija
 * docs/api-rest.md §1.
 *
 * No se devuelve el Page de Spring Data directamente a propósito: su
 * serialización JSON no es parte del contrato de Spring —cambia entre
 * versiones y arrastra al cuerpo detalles internos como "pageable" o "sort"—,
 * así que los clientes acabarían atados a una estructura que no controlamos.
 * Este DTO fija los seis campos documentados y nada más.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginaResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;

    /** Convierte una página de entidades en una página de DTOs. */
    public static <E, T> PaginaResponse<T> de(Page<E> pagina, Function<E, T> aDto) {
        return PaginaResponse.<T>builder()
                .content(pagina.getContent().stream().map(aDto).toList())
                .page(pagina.getNumber())
                .size(pagina.getSize())
                .totalElements(pagina.getTotalElements())
                .totalPages(pagina.getTotalPages())
                .last(pagina.isLast())
                .build();
    }
}
