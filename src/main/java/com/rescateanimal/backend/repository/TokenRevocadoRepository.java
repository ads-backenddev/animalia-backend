package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.TokenRevocado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Acceso a los tokens invalidados antes de caducar (RF-16, RF-17).
 */
public interface TokenRevocadoRepository extends JpaRepository<TokenRevocado, String> {

    /**
     * ¿Sigue revocado este token, es decir, consta y además no ha caducado ya
     * por su cuenta?
     *
     * La caducidad entra en la misma consulta en vez de comprobarse después,
     * porque una fila caducada es exactamente igual de irrelevante que una
     * ausente: a partir de ese instante el token lo rechaza la validación de la
     * firma. Resolverlo aquí evita traer la fila para acabar descartándola.
     *
     * Es una búsqueda por clave primaria, que es lo que la hace aceptable en el
     * camino de CADA petición autenticada.
     */
    boolean existsByJtiAndExpiraEnAfter(String jti, Instant momento);

    /**
     * Borra las revocaciones de tokens que ya han caducado por sí solos.
     *
     * A partir de su caducidad el token lo rechaza la propia validación de la
     * firma, así que recordarlo no aporta nada y la tabla crecería sin límite.
     *
     * Es un borrado en bloque y no un findAll + deleteAll a propósito: lo
     * resuelve una sola sentencia en la base de datos, sin traerse las filas a
     * memoria para volver a mandarlas de vuelta.
     */
    @Modifying
    @Query("DELETE FROM TokenRevocado t WHERE t.expiraEn < :momento")
    int purgarCaducadosAntesDe(@Param("momento") Instant momento);
}
