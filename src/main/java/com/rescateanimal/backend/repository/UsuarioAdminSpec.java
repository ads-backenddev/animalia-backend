package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtros del listado de usuarios del panel de administración (RF-11),
 * combinables entre sí y los dos opcionales.
 *
 * Es una Specification y no JPQL por lo mismo que CatalogoAdopcionSpec, y el
 * motivo está documentado allí en detalle: con el patrón ":x IS NULL OR ..." un
 * parámetro nulo no tiene tipo que PostgreSQL pueda deducir, y además obliga a
 * mantener dos consultas —la principal y la del recuento— que pueden
 * desincronizarse. Aquí el predicado sencillamente no se añade cuando el filtro
 * falta, y Spring Data deriva el recuento de la misma descripción.
 *
 * Sin ningún filtro devuelve todos los usuarios, que es lo que pide el listado
 * por defecto.
 */
public final class UsuarioAdminSpec {

    private UsuarioAdminSpec() {
    }

    /**
     * @param rol    rol exacto, o null para no filtrar por rol
     * @param activo true = solo cuentas operativas, false = solo bloqueadas,
     *               null = ambas. Son tres respuestas distintas y por eso es un
     *               Boolean y no un boolean: "no filtrar" no puede confundirse
     *               con "filtrar por false"
     */
    public static Specification<Usuario> de(Rol rol, Boolean activo) {
        return (raiz, consulta, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();

            if (rol != null) {
                condiciones.add(cb.equal(raiz.get("rol"), rol));
            }

            if (activo != null) {
                condiciones.add(cb.equal(raiz.get("activo"), activo));
            }

            // Sin condiciones, cb.and() de un array vacío es la conjunción vacía,
            // que en JPA Criteria vale "verdadero": devuelve todo, que es lo que
            // corresponde cuando no se filtra nada.
            return cb.and(condiciones.toArray(new Predicate[0]));
        };
    }
}
