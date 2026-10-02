package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.LimiteFotoDiario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * Contador diario de subidas de fotos. Las dos escrituras son las mismas que
 * las de LimiteMatchDiarioRepository, y por lo mismo: nativas y atómicas, para
 * que dos subidas simultáneas no lean el mismo valor y pasen del límite entre
 * las dos. El límite lo decide FotoService y llega como parámetro.
 */
public interface LimiteFotoDiarioRepository extends JpaRepository<LimiteFotoDiario, Long> {

    /** Crea la fila del día si aún no existe, sin fallar si otra petición se adelantó. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO limite_foto_diario (usuario_id, fecha, contador)
            VALUES (:usuarioId, :fecha, 0)
            ON CONFLICT (usuario_id, fecha) DO NOTHING
            """, nativeQuery = true)
    void crearContadorSiNoExiste(@Param("usuarioId") Long usuarioId,
                                 @Param("fecha") LocalDate fecha);

    /**
     * Suma una subida solo si queda cupo, en una única sentencia. Devuelve 1 si
     * la ha sumado y 0 si el límite ya estaba alcanzado.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE limite_foto_diario
            SET contador = contador + 1
            WHERE usuario_id = :usuarioId
              AND fecha = :fecha
              AND contador < :limite
            """, nativeQuery = true)
    int incrementarSiHayCupo(@Param("usuarioId") Long usuarioId,
                             @Param("fecha") LocalDate fecha,
                             @Param("limite") int limite);
}
