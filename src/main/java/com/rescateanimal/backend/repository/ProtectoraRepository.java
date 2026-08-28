package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Capa de datos de Protectora. Sin lógica de negocio.
 */
@Repository
public interface ProtectoraRepository extends JpaRepository<Protectora, Long> {

    /** El login necesita el estado de verificación de la protectora del usuario. */
    Optional<Protectora> findByUsuarioId(Long usuarioId);

    /**
     * Protectoras en un estado dado, con su cuenta ya cargada, más antiguas
     * primero (RF-11).
     *
     * Lo usa la cola de verificación del administrador. El orden es ascendente a
     * propósito, al revés que el resto de listados de la API: esto es una cola de
     * trabajo, y quien lleva más tiempo esperando es a quien toca atender —una
     * protectora que no puede operar hasta que alguien la mire—. Ordenarla por lo
     * más reciente dejaría a las primeras al final indefinidamente.
     *
     * El usuario viene con JOIN FETCH porque la respuesta muestra su nombre y su
     * email; sin él habría una consulta por protectora.
     */
    @Query("""
            SELECT p FROM Protectora p
            JOIN FETCH p.usuario
            WHERE p.estado = :estado
            ORDER BY p.createdAt ASC
            """)
    List<Protectora> buscarPorEstadoConCuenta(@Param("estado") EstadoProtectora estado);

    /**
     * Una protectora con su cuenta ya cargada.
     *
     * Aprobar o rechazar necesita al usuario para notificarle la decisión, y sin
     * el fetch ese acceso dispararía una consulta perezosa más.
     */
    @Query("""
            SELECT p FROM Protectora p
            JOIN FETCH p.usuario
            WHERE p.id = :id
            """)
    Optional<Protectora> buscarConCuenta(@Param("id") Long id);

    /**
     * Página del directorio público de protectoras (RF-06), ordenada por nombre.
     *
     * DOS CONDICIONES DE VISIBILIDAD, Y NINGUNA SOBRA:
     *
     * - {@code p.estado IN :estados} deja fuera a las PENDIENTE y a las
     *   RECHAZADA. La lista de estados la calcula ProtectoraService a partir de
     *   un switch exhaustivo, no se escribe aquí: así un valor nuevo en
     *   EstadoProtectora rompe la compilación en vez de colarse en el directorio.
     *
     * - {@code u.activo = true} deja fuera a las cuentas inhabilitadas, que es
     *   un caso que el estado NO cubre y que se descubrió construyendo esto. Una
     *   protectora que se da de baja (RF-19) conserva su {@code estado = ACTIVA}
     *   —la baja anonimiza al usuario y retira sus animales, pero no toca la fila
     *   de PROTECTORA— y una bloqueada por el administrador (RF-11) tampoco lo
     *   cambia. Sin esta condición, el directorio publicaría la ficha de una
     *   protectora que ya no existe, con su email anonimizado
     *   "@cuenta-eliminada.invalid" ofrecido como forma de contactarla.
     *
     * EL FILTRO DE ZONA LLEGA YA CONVERTIDO EN PATRÓN LIKE, y no como un texto
     * que aquí pueda ser nulo. Es la lección que dejó escrita CatalogoAdopcionSpec
     * y que costó un fallo contra PostgreSQL: un parámetro nulo no tiene tipo, y
     * {@code lower(:zona)} con :zona a null se toma por bytea y revienta con
     * "function lower(bytea) does not exist". Aquí el parámetro es SIEMPRE una
     * cadena —"%" cuando no se filtra, "%madrid%" cuando sí— de modo que nunca se
     * enlaza un nulo y la consulta es una sola, sin ramas.
     *
     * El {@code coalesce} es para que una protectora sin zona declarada no
     * desaparezca del listado sin filtro: {@code NULL LIKE '%'} es NULL, no
     * cierto, y la fila se caería.
     *
     * OJO AL TOCARLA: el countQuery es explícito porque el JOIN FETCH no puede
     * viajar en una consulta de recuento, así que la condición está escrita dos
     * veces y las dos tienen que decir lo mismo. Un filtro añadido solo a la de
     * arriba dejaría el total de páginas mal sin que nada lo delatara.
     */
    @Query(value = """
            SELECT p FROM Protectora p
            JOIN FETCH p.usuario u
            WHERE p.estado IN :estados
              AND u.activo = true
              AND lower(coalesce(p.zonaGeografica, '')) LIKE :zona
            ORDER BY lower(p.nombreOrganizacion) ASC, p.id ASC
            """,
            countQuery = """
                    SELECT COUNT(p) FROM Protectora p
                    JOIN p.usuario u
                    WHERE p.estado IN :estados
                      AND u.activo = true
                      AND lower(coalesce(p.zonaGeografica, '')) LIKE :zona
                    """)
    Page<Protectora> buscarParaDirectorio(@Param("estados") Collection<EstadoProtectora> estados,
                                          @Param("zona") String zona,
                                          Pageable paginacion);
}
