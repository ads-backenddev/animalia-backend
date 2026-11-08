package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.LimiteAvistamientoDiario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * Contador diario de avistamientos por usuario y alerta. Las dos escrituras son
 * las de LimiteMatchDiarioRepository y LimiteFotoDiarioRepository, y por lo
 * mismo: nativas y atómicas, para que dos registros simultáneos no lean el
 * mismo valor y pasen del límite entre los dos. El límite lo decide
 * AvistamientoService y llega como parámetro.
 *
 * Sin clearAutomatically, como las otras dos: vaciar el contexto de
 * persistencia desprendería la alerta que el service ya tiene cargada y
 * bloqueada.
 */
public interface LimiteAvistamientoDiarioRepository extends JpaRepository<LimiteAvistamientoDiario, Long> {

    /** Crea la fila del día si aún no existe, sin fallar si otra petición se adelantó. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO limite_avistamiento_diario (usuario_id, alerta_id, fecha, contador)
            VALUES (:usuarioId, :alertaId, :fecha, 0)
            ON CONFLICT (usuario_id, alerta_id, fecha) DO NOTHING
            """, nativeQuery = true)
    void crearContadorSiNoExiste(@Param("usuarioId") Long usuarioId,
                                 @Param("alertaId") Long alertaId,
                                 @Param("fecha") LocalDate fecha);

    /**
     * Suma un avistamiento solo si queda cupo, en una única sentencia. Devuelve
     * 1 si lo ha sumado y 0 si el límite ya estaba alcanzado.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE limite_avistamiento_diario
            SET contador = contador + 1
            WHERE usuario_id = :usuarioId
              AND alerta_id = :alertaId
              AND fecha = :fecha
              AND contador < :limite
            """, nativeQuery = true)
    int incrementarSiHayCupo(@Param("usuarioId") Long usuarioId,
                             @Param("alertaId") Long alertaId,
                             @Param("fecha") LocalDate fecha,
                             @Param("limite") int limite);
}
