package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Foto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Acceso al registro de fotos subidas.
 */
public interface FotoRepository extends JpaRepository<Foto, String> {

    /**
     * ¿Es esta foto de este usuario? Es la comprobación de propiedad de cada
     * fotoUrl recibido, y va por la clave primaria.
     */
    boolean existsByNombreAndUsuarioId(String nombre, Long usuarioId);

    /**
     * ¿Usa esta foto alguna publicación —alerta, avistamiento o animal en
     * adopción—? El perfil no entra: es justo de donde se está quitando.
     *
     * Se compara por el nombre del archivo y no por la URL entera, por lo mismo
     * que en sinUsoAnterioresA: la base pública de la URL depende del entorno.
     *
     * Una sola consulta con tres EXISTS, que se cortan en la primera fila que
     * encuentran. Ninguna de las tres columnas foto_url tiene índice, así que
     * cada EXISTS que no encuentra nada recorre su tabla; se acepta porque solo
     * se pregunta al cambiar una foto de perfil o al dar de baja una cuenta.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM alerta WHERE substring(foto_url FROM '[^/]*$') = :nombre)
                OR EXISTS (SELECT 1 FROM avistamiento WHERE substring(foto_url FROM '[^/]*$') = :nombre)
                OR EXISTS (SELECT 1 FROM animal_adopcion WHERE substring(foto_url FROM '[^/]*$') = :nombre)
            """, nativeQuery = true)
    boolean usadaEnPublicaciones(@Param("nombre") String nombre);

    /**
     * Las fotos subidas antes de limite a las que no apunta ningún foto_url: ni
     * un perfil ni una publicación. Son subidas que el cliente nunca llegó a
     * usar, y las recoge la limpieza diaria.
     *
     * SE COMPARA POR EL NOMBRE DEL ARCHIVO, el último tramo de la URL, y no por
     * la URL entera. La URL lleva la base pública del entorno que la guardó, y
     * esa base cambia: si el dominio de Railway cambiara, todas las fotos
     * parecerían sin uso y se borrarían. Y las pruebas comparten la base de
     * datos de desarrollo con otra base pública: comparando URLs, una limpieza
     * lanzada desde una prueba borraría las fotos que se usan en desarrollo. El
     * nombre es un UUID: ninguna URL externa termina igual por casualidad.
     *
     * NOT IN contra la unión de las cuatro columnas, no cuatro NOT EXISTS por
     * foto: PostgreSQL construye la unión una vez y la busca como tabla hash, en
     * vez de recorrer las cuatro tablas —sin índice en foto_url— por cada foto
     * candidata. Los IS NOT NULL no son decoración: un solo NULL dentro de un
     * NOT IN hace que no devuelva nada nunca.
     */
    @Query(value = """
            SELECT f.nombre FROM foto f
            WHERE f.created_at < :limite
              AND f.nombre NOT IN (
                    SELECT substring(foto_url FROM '[^/]*$') FROM (
                        SELECT foto_url FROM usuario
                        UNION ALL SELECT foto_url FROM alerta
                        UNION ALL SELECT foto_url FROM avistamiento
                        UNION ALL SELECT foto_url FROM animal_adopcion
                    ) usadas
                    WHERE foto_url IS NOT NULL)
            """, nativeQuery = true)
    List<String> sinUsoAnterioresA(@Param("limite") Instant limite);

    /** De estos nombres, los que tienen fila. Para cruzar el disco con la tabla de una vez. */
    @Query("SELECT f.nombre FROM Foto f WHERE f.nombre IN :nombres")
    List<String> conFila(@Param("nombres") Collection<String> nombres);
}
