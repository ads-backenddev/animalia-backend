package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtros del listado de alertas (GET /api/alertas), con el mismo patrón que
 * CatalogoAdopcionSpec: cada filtro que llega en blanco equivale a no filtrar,
 * y el estado no es opcional, porque qué estados se pueden listar lo decide
 * AlertaService, no el cliente.
 *
 * LA ZONA ES UN CÍRCULO, NO UN TEXTO. Las alertas no tienen zona escrita, como
 * los animales: tienen el punto donde se vio al animal. El areaGeografica del
 * autor sería su área de perfil, opcional y que no dice dónde se perdió el
 * animal. Así que el filtro es el mismo de /mapa: centro y radio en metros.
 *
 * ST_DistanceSphere y no ST_DWithin sobre geography, que es lo que usa el mapa:
 * desde Criteria no se puede escribir el CAST a geography, y ST_DistanceSphere
 * da metros reales sobre una esfera, con un error despreciable a 50 km. No usa
 * el índice GiST, y se acepta: el listado va paginado y filtra antes por estado,
 * así que recorre un conjunto ya acotado.
 */
public final class AlertaSpec {

    private static final int SRID_WGS84 = 4326;

    private AlertaSpec() {
    }

    /**
     * @param estado   estado exigido; nunca es opcional
     * @param especie  texto libre, se compara sin distinguir mayúsculas
     * @param latitud  centro del área, o null para no filtrar por zona; si lo
     *                 es, longitud y radioMetros también —lo valida el service—
     */
    public static Specification<Alerta> de(EstadoAlerta estado, String especie,
                                           Double latitud, Double longitud, Double radioMetros) {

        return (raiz, consulta, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            condiciones.add(cb.equal(raiz.get("estado"), estado));

            String especieBuscada = normalizar(especie);
            if (especieBuscada != null) {
                condiciones.add(cb.equal(cb.lower(raiz.get("especie")), especieBuscada.toLowerCase()));
            }

            if (latitud != null) {
                // ST_MakePoint toma (x, y), es decir (longitud, latitud).
                Expression<Geometry> centro = cb.function("ST_SetSRID", Geometry.class,
                        cb.function("ST_MakePoint", Geometry.class, cb.literal(longitud), cb.literal(latitud)),
                        cb.literal(SRID_WGS84));
                Expression<Double> distancia = cb.function("ST_DistanceSphere", Double.class,
                        raiz.get("ultimaUbicacion"), centro);
                condiciones.add(cb.isNotNull(raiz.get("ultimaUbicacion")));
                condiciones.add(cb.lessThanOrEqualTo(distancia, radioMetros));
            }

            return cb.and(condiciones.toArray(new Predicate[0]));
        };
    }

    private static String normalizar(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
