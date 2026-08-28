package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Valoracion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de Valoracion. Sin lógica de negocio.
 */
@Repository
public interface ValoracionRepository extends JpaRepository<Valoracion, Long> {

    /**
     * La valoración que un usuario ya emitió sobre una protectora, si la hay.
     *
     * Es lo que hace idempotente a PUT /api/protectoras/{id}/valoracion: volver
     * a valorar actualiza esta fila en vez de crear una segunda. La restricción
     * UNIQUE de V9 respalda la intención para el caso de dos peticiones
     * simultáneas, que esta consulta por sí sola no puede cubrir.
     */
    Optional<Valoracion> findByUsuarioIdAndProtectoraId(Long usuarioId, Long protectoraId);

    /**
     * Valoraciones recibidas por una protectora, más recientes primero (RF-06,
     * RF-10).
     *
     * El usuario viene con JOIN FETCH porque quien las lee pinta quién valoró;
     * sin él habría una consulta por valoración. Es una asociación de un solo
     * valor, así que el límite del Pageable lo aplica el SQL y no se resuelve en
     * memoria, como sí ocurriría con una colección.
     *
     * EL LÍMITE ES DE QUIEN LLAMA, y esa es la única diferencia entre sus dos
     * usos. El panel de RF-10 pasa {@code Pageable.unpaged()} porque una
     * protectora quiere su histórico entero; la ficha pública del directorio pasa
     * un tope, porque se sirve en cada visita y con las últimas basta. Una sola
     * consulta y dos políticas, igual que hace el catálogo de adopción con
     * CatalogoAdopcionSpec: escribirla dos veces habría acabado en dos consultas
     * que se parecen y que se separan en cuanto una de las dos cambie.
     *
     * El desempate por id no es cosmético: sin él, dos valoraciones con el mismo
     * createdAt podrían salir en distinto orden en dos llamadas y una página
     * repetiría o se saltaría filas.
     */
    @Query("""
            SELECT v FROM Valoracion v
            JOIN FETCH v.usuario
            WHERE v.protectora.id = :protectoraId
            ORDER BY v.createdAt DESC, v.id DESC
            """)
    List<Valoracion> buscarDeProtectoraConAutor(@Param("protectoraId") Long protectoraId,
                                                Pageable limite);

    /**
     * Media y recuento de VARIAS protectoras de una vez (RF-06, RF-23).
     *
     * EXISTE PARA QUE EL DIRECTORIO NO SEA UN N+1. Su listado devuelve una
     * página de protectoras y cada una lleva su nota: resolverla con
     * mediaDeProtectora y countByProtectoraId dentro del bucle habría costado
     * DOS consultas por fila, cuarenta en una página de veinte. Aquí es una sola,
     * agrupada, apoyada en el índice idx_valoracion_protectora que creó V10.
     *
     * LAS PROTECTORAS SIN NINGUNA VALORACIÓN NO SALEN EN EL RESULTADO, porque un
     * GROUP BY no inventa grupos vacíos. No es un problema que haya que arreglar
     * con un LEFT JOIN desde PROTECTORA: quien llama las busca en un mapa, y no
     * encontrarlas significa exactamente lo que tiene que significar —ninguna
     * valoración, media nula y total cero—. Lo resuelve ValoracionService.
     *
     * Devuelve AVG como Double, igual que mediaDeProtectora: el redondeo a la
     * precisión que expone la API lo decide el service, no la consulta.
     */
    @Query("""
            SELECT v.protectora.id AS protectoraId,
                   AVG(v.puntuacion) AS media,
                   COUNT(v) AS total
            FROM Valoracion v
            WHERE v.protectora.id IN :protectoraIds
            GROUP BY v.protectora.id
            """)
    List<ResumenPorProtectora> resumirPorProtectora(
            @Param("protectoraIds") Collection<Long> protectoraIds);

    /** Una fila de resumirPorProtectora: a quién corresponde la media y sobre cuántas. */
    interface ResumenPorProtectora {
        Long getProtectoraId();

        Double getMedia();

        long getTotal();
    }

    /**
     * Media de las puntuaciones de una protectora, o null si no tiene ninguna.
     *
     * Existe además del listado para que el resumen del panel no tenga que
     * traerse todas las valoraciones solo para promediarlas. Devuelve Double
     * —lo que da AVG— y no BigDecimal: el redondeo a la precisión que expone la
     * API lo decide el service, no la consulta.
     */
    @Query("SELECT AVG(v.puntuacion) FROM Valoracion v WHERE v.protectora.id = :protectoraId")
    Double mediaDeProtectora(@Param("protectoraId") Long protectoraId);

    /** Cuántas valoraciones tiene la protectora, para saber si la media es significativa. */
    long countByProtectoraId(Long protectoraId);
}
