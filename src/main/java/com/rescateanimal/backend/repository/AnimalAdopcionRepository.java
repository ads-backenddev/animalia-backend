package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.Tamanyo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de AnimalAdopcion. Sin lógica de negocio.
 */
@Repository
public interface AnimalAdopcionRepository
        extends JpaRepository<AnimalAdopcion, Long>, JpaSpecificationExecutor<AnimalAdopcion> {

    /**
     * Catálogo filtrado (RF-22). Los criterios los describe CatalogoAdopcionSpec.
     *
     * Se sobrescribe solo para colgarle el grafo de entidad: sin él, pintar el
     * nombre de la protectora en cada tarjeta dispararía una consulta por fila
     * (N+1). Es seguro al paginar porque `protectora` es una asociación a-uno:
     * cada fila del resultado sigue siendo un animal, así que el LIMIT del SQL
     * cuenta lo mismo que la página.
     *
     * El recuento lo deriva Spring Data de la misma Specification, así que no
     * hay dos copias de los filtros que puedan separarse con el tiempo. El grafo
     * no le afecta: contar no necesita traerse la protectora.
     */
    @Override
    @EntityGraph(attributePaths = "protectora")
    Page<AnimalAdopcion> findAll(Specification<AnimalAdopcion> filtros, Pageable paginacion);

    /**
     * Un animal con su protectora y la cuenta de esta ya cargadas.
     *
     * Las necesitan tanto el detalle —que pinta los datos de la organización—
     * como el match, que tiene que notificar a protectora.usuario: sin el fetch,
     * ese segundo salto dispararía dos consultas perezosas más.
     */
    @Query("""
            SELECT a FROM AnimalAdopcion a
            JOIN FETCH a.protectora p
            JOIN FETCH p.usuario
            WHERE a.id = :id
            """)
    Optional<AnimalAdopcion> buscarConProtectora(@Param("id") Long id);

    /**
     * Lo mismo para un lote de ids, en una sola consulta.
     *
     * La usa el listado de chats para resolver de golpe los animales de todas
     * las conversaciones de adopción del usuario, en vez de uno por chat (N+1).
     */
    @Query("""
            SELECT a FROM AnimalAdopcion a
            JOIN FETCH a.protectora p
            JOIN FETCH p.usuario
            WHERE a.id IN :ids
            """)
    List<AnimalAdopcion> buscarConProtectoraPorIds(@Param("ids") Collection<Long> ids);

    /**
     * Cuántos animales tiene una protectora en cada estado (RF-10).
     *
     * Una sola consulta agregada en vez de tres recuentos o, peor, de traerse el
     * catálogo entero para contarlo en Java: el resumen del panel no necesita ni
     * un animal en memoria. Se apoya en idx_animal_protectora, creado en V4.
     *
     * Solo devuelve fila para los estados que la protectora tenga; los que no
     * aparezcan valen cero, y de convertir esa ausencia en un cero se encarga
     * ProtectoraPanelService.
     */
    @Query("""
            SELECT a.estado AS estado, COUNT(a) AS total
            FROM AnimalAdopcion a
            WHERE a.protectora.id = :protectoraId
            GROUP BY a.estado
            """)
    List<ConteoPorEstado> contarPorEstado(@Param("protectoraId") Long protectoraId);

    /** Proyección del recuento agrupado: evita manejar Object[] en el service. */
    interface ConteoPorEstado {
        EstadoAnimal getEstado();

        long getTotal();
    }

    /**
     * Animales de una protectora en un estado concreto.
     *
     * La usa la baja de cuenta (RF-19) para retirar del catálogo lo que la
     * protectora dejaba publicado: quien lo ofrecía se ha ido, así que nadie puede
     * responder a una solicitud ni confirmar una adopción. Se pide por estado en
     * vez de traer todos y filtrar en memoria porque solo interesan los
     * DISPONIBLE; los ADOPTADO son un desenlace que no hay que deshacer.
     */
    List<AnimalAdopcion> findByProtectoraIdAndEstado(Long protectoraId, EstadoAnimal estado);
}
