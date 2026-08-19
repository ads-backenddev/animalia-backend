package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.Tamanyo;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtros del catálogo de adopción (RF-22), combinables entre sí.
 *
 * POR QUÉ UNA SPECIFICATION Y NO JPQL. Antes esto eran dos consultas escritas a
 * mano —la principal y la de recuento— con el patrón ":x IS NULL OR ...". Tenía
 * dos problemas, y los dos los destapó ejecutarla contra PostgreSQL de verdad:
 *
 * 1. Un parámetro nulo no tiene tipo. Al comparar `lower(a.especie) =
 *    lower(:especie)` con :especie a null, PostgreSQL no puede deducir de qué
 *    tipo es el parámetro, lo toma por bytea y falla con "function lower(bytea)
 *    does not exist". Aquí el predicado no se añade cuando el filtro falta, así
 *    que no se llega a enlazar ningún nulo.
 * 2. Las dos consultas podían desincronizarse. Un cuarto filtro añadido solo a
 *    la principal habría dejado el total de páginas mal sin que nada lo
 *    delatara. Ahora hay una sola descripción de los filtros y Spring Data
 *    deriva de ella tanto la consulta como su recuento.
 *
 * Un filtro en blanco se trata como filtro ausente: "?especie=" significa "sin
 * filtrar", no "los que tengan la especie vacía".
 */
public final class CatalogoAdopcionSpec {

    private CatalogoAdopcionSpec() {
    }

    /**
     * @param estado  estado exigido; nunca es opcional, el catálogo solo enseña
     *                los DISPONIBLE y eso no lo decide el cliente
     * @param especie texto libre, se compara sin distinguir mayúsculas
     * @param tamanyo valor exacto del enum
     * @param zona    se busca por contenido: "Madrid" encuentra "Madrid, Carabanchel"
     */
    public static Specification<AnimalAdopcion> de(EstadoAnimal estado, String especie,
                                                   Tamanyo tamanyo, String zona) {

        return (raiz, consulta, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            condiciones.add(cb.equal(raiz.get("estado"), estado));

            String especieBuscada = normalizar(especie);
            if (especieBuscada != null) {
                condiciones.add(cb.equal(
                        cb.lower(raiz.get("especie")), especieBuscada.toLowerCase()));
            }

            if (tamanyo != null) {
                condiciones.add(cb.equal(raiz.get("tamanyo"), tamanyo));
            }

            String zonaBuscada = normalizar(zona);
            if (zonaBuscada != null) {
                condiciones.add(cb.like(
                        cb.lower(raiz.get("zonaGeografica")),
                        "%" + zonaBuscada.toLowerCase() + "%"));
            }

            return cb.and(condiciones.toArray(new Predicate[0]));
        };
    }

    /** Un filtro vacío o en blanco es un filtro que no se aplica. */
    private static String normalizar(String filtro) {
        if (filtro == null || filtro.isBlank()) {
            return null;
        }
        return filtro.trim();
    }
}
