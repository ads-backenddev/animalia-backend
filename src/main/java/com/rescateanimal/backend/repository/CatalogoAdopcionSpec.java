package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.Tamanyo;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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

    /**
     * Lo mismo, pero para el feed de swipe (RF-09, RF-15): además de los
     * filtros, deja fuera lo que este usuario no debe volver a ver.
     *
     * Dos exclusiones, cada una por su motivo:
     * - los animales de su propia protectora, porque nadie hace match consigo
     *   mismo y MatchService rechazaría el swipe con 403;
     * - lo que ya deslizó, en cualquiera de los dos sentidos, que es justo lo
     *   que deja registrado MATCH_REGISTRO. Sin esto, un descarte volvería a
     *   aparecer en la siguiente sesión y la restricción única convertiría el
     *   segundo swipe en un 409.
     *
     * El NOT EXISTS filtra por tipo además de por id: publicacion_id es una
     * referencia polimórfica, así que sin ese filtro haber deslizado el
     * avistamiento 7 escondería del feed al animal 7, que no tiene nada que ver.
     * Se apoya en el índice único (usuario_id, publicacion_id, tipo_publicacion),
     * que cubre las tres columnas de la subconsulta.
     */
    public static Specification<AnimalAdopcion> paraFeed(EstadoAnimal estado, String especie,
                                                         Tamanyo tamanyo, String zona,
                                                         Long usuarioId) {

        return de(estado, especie, tamanyo, zona).and((raiz, consulta, cb) -> {
            Subquery<Long> yaDeslizado = consulta.subquery(Long.class);
            Root<MatchRegistro> match = yaDeslizado.from(MatchRegistro.class);
            yaDeslizado.select(cb.literal(1L)).where(
                    cb.equal(match.get("usuario").get("id"), usuarioId),
                    cb.equal(match.get("tipoPublicacion"), TipoPublicacion.ADOPCION),
                    cb.equal(match.get("publicacionId"), raiz.get("id")));

            return cb.and(
                    cb.notEqual(raiz.get("protectora").get("usuario").get("id"), usuarioId),
                    cb.not(cb.exists(yaDeslizado)));
        });
    }

    /**
     * Los animales de UNA protectora, en cualquier estado (RF-10).
     *
     * Es el catálogo del panel de gestión, y se diferencia del público en las
     * dos cosas a la vez: acota a una protectora, y NO filtra por estado. Esa
     * ausencia es el punto: el catálogo de RF-22 solo enseña los DISPONIBLE
     * porque un animal adoptado no es escaparate, pero su protectora sí tiene
     * que verlos todos —también los RETIRADO— porque es su histórico de
     * gestión. Por eso no se reutiliza `de(...)`, que exige un estado.
     *
     * Se filtra por el id de la PROTECTORA y no por el de su cuenta de usuario,
     * a diferencia de paraFeed: quien llama ya la ha resuelto para comprobar que
     * está ACTIVA, así que este salto de más sobraría.
     */
    public static Specification<AnimalAdopcion> deLaProtectora(Long protectoraId) {
        return (raiz, consulta, cb) -> cb.equal(raiz.get("protectora").get("id"), protectoraId);
    }

    /** Un filtro vacío o en blanco es un filtro que no se aplica. */
    private static String normalizar(String filtro) {
        if (filtro == null || filtro.isBlank()) {
            return null;
        }
        return filtro.trim();
    }
}
