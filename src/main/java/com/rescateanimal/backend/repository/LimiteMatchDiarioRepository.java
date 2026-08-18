package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.LimiteMatchDiario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Capa de datos del contador diario de matches (RF-24).
 *
 * Las dos operaciones de escritura son nativas y sin condición de carrera a
 * propósito: leer el contador, decidir en Java y volver a guardarlo permitiría
 * que dos peticiones simultáneas leyeran el mismo valor y superaran el límite
 * entre las dos. La decisión de negocio —cuál es el límite— sigue estando en
 * MatchService, que lo pasa como parámetro.
 *
 * Ninguna de las dos lleva clearAutomatically: vaciar el contexto de
 * persistencia desprendería las entidades que el service ya tiene cargadas
 * (el avistamiento y su alerta) y el siguiente acceso perezoso fallaría. Aquí
 * no hace falta, porque el contador no se maneja como entidad en esa ruta.
 */
@Repository
public interface LimiteMatchDiarioRepository extends JpaRepository<LimiteMatchDiario, Long> {

    Optional<LimiteMatchDiario> findByUsuarioIdAndFecha(Long usuarioId, LocalDate fecha);

    /**
     * Crea la fila del día si aún no existe, sin fallar si otra petición se
     * adelantó. ON CONFLICT DO NOTHING se apoya en la restricción única
     * (usuario_id, fecha) y evita tener que capturar una violación de
     * integridad, que dejaría inservible la sesión de Hibernate.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO limite_match_diario (usuario_id, fecha, contador)
            VALUES (:usuarioId, :fecha, 0)
            ON CONFLICT (usuario_id, fecha) DO NOTHING
            """, nativeQuery = true)
    void crearContadorSiNoExiste(@Param("usuarioId") Long usuarioId,
                                 @Param("fecha") LocalDate fecha);

    /**
     * Incrementa el contador solo si queda cupo, en una única sentencia atómica.
     * Devuelve 1 si consumió cuota y 0 si el límite ya estaba alcanzado, que es
     * lo que MatchService traduce a 429.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE limite_match_diario
            SET contador = contador + 1
            WHERE usuario_id = :usuarioId
              AND fecha = :fecha
              AND contador < :limite
            """, nativeQuery = true)
    int incrementarSiHayCupo(@Param("usuarioId") Long usuarioId,
                             @Param("fecha") LocalDate fecha,
                             @Param("limite") int limite);
}
