package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Capa de datos de MatchRegistro. Sin lógica de negocio.
 */
@Repository
public interface MatchRegistroRepository extends JpaRepository<MatchRegistro, Long> {

    /**
     * ¿Ya deslizó este usuario esta publicación? Da igual el sentido: la
     * restricción única no distingue entre match y descarte.
     */
    boolean existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(Long usuarioId,
                                                                Long publicacionId,
                                                                TipoPublicacion tipoPublicacion);

    /**
     * Matches del usuario, más recientes primero. Solo los positivos: los
     * descartes viven en la misma tabla pero no son matches y no interesan al
     * listado (RF-08).
     */
    List<MatchRegistro> findByUsuarioIdAndInteresadoTrueOrderByCreatedAtDesc(Long usuarioId);

    /**
     * Solicitudes de adopción recibidas por una protectora (RF-10): los
     * ciudadanos que han mostrado interés por alguno de sus animales.
     *
     * Es el reverso del listado anterior. Aquel mira los matches DE un usuario;
     * este los matches SOBRE los animales de una protectora, que no cuelgan de
     * ella por ninguna asociación: hay que saltar por publicacionId hasta
     * ANIMAL_ADOPCION y desde ahí a la protectora.
     *
     * interesado = true no es un filtro cosmético: los descartes de RF-15
     * comparten tabla con los matches y contarlos como solicitudes le diría a la
     * protectora que alguien quiere a un animal al que en realidad dijo que no.
     *
     * El EXISTS empareja publicacionId con el tipo, sin excepción, por lo mismo
     * que ChatRepository y CatalogoAdopcionSpec: publicacion_id es una
     * referencia polimórfica y el id 7 existe como avistamiento y como animal.
     * Sin el filtro de tipo, deslizar el avistamiento 7 aparecería aquí como una
     * solicitud sobre el animal 7, que no tiene nada que ver.
     *
     * El usuario viene con JOIN FETCH porque el listado pinta el nombre y la
     * foto de cada interesado; sin él serían tantas consultas como solicitudes.
     */
    @Query("""
            SELECT m FROM MatchRegistro m
            JOIN FETCH m.usuario
            WHERE m.tipoPublicacion = :adopcion
              AND m.interesado = true
              AND EXISTS (SELECT 1 FROM AnimalAdopcion a
                          WHERE a.id = m.publicacionId
                            AND a.protectora.id = :protectoraId)
            ORDER BY m.createdAt DESC
            """)
    List<MatchRegistro> buscarSolicitudesDeProtectora(@Param("protectoraId") Long protectoraId,
                                                      @Param("adopcion") TipoPublicacion adopcion);

    /** Cuántas solicitudes ha recibido la protectora en total (RF-10). */
    @Query("""
            SELECT COUNT(m) FROM MatchRegistro m
            WHERE m.tipoPublicacion = :adopcion
              AND m.interesado = true
              AND EXISTS (SELECT 1 FROM AnimalAdopcion a
                          WHERE a.id = m.publicacionId
                            AND a.protectora.id = :protectoraId)
            """)
    long contarSolicitudesDeProtectora(@Param("protectoraId") Long protectoraId,
                                       @Param("adopcion") TipoPublicacion adopcion);

    /**
     * Solicitudes que la protectora todavía tiene que atender: las que recaen
     * sobre un animal que sigue en el estado indicado (DISPONIBLE).
     *
     * El estado llega como parámetro y no escrito en la consulta porque cuál es
     * el estado "pendiente" es una decisión de negocio, y esa vive en
     * ProtectoraPanelService. Aquí solo se sabe filtrar por él.
     */
    @Query("""
            SELECT COUNT(m) FROM MatchRegistro m
            WHERE m.tipoPublicacion = :adopcion
              AND m.interesado = true
              AND EXISTS (SELECT 1 FROM AnimalAdopcion a
                          WHERE a.id = m.publicacionId
                            AND a.protectora.id = :protectoraId
                            AND a.estado = :estadoPendiente)
            """)
    long contarSolicitudesPendientes(@Param("protectoraId") Long protectoraId,
                                     @Param("adopcion") TipoPublicacion adopcion,
                                     @Param("estadoPendiente") EstadoAnimal estadoPendiente);

    /**
     * Si el usuario ha llegado a contactar alguna vez con esa protectora (RF-23).
     *
     * ES LA CONDICIÓN QUE HACE QUE UNA VALORACIÓN SIGNIFIQUE ALGO. RF-23 no pide
     * una valoración libre sino una "tras una adopción o contacto", y sin esta
     * comprobación cualquiera podría puntuar a cualquier protectora sin haber
     * tratado con ella: bastaría un script para hundir o inflar la reputación de
     * quien se quisiera, que es precisamente lo que una valoración pretende medir.
     *
     * EL CRITERIO ES EL INTERÉS DECLARADO SOBRE UNO DE SUS ANIMALES, es decir, un
     * deslizamiento a favor sobre una publicación de adopción suya. Se eligió ese
     * y no la adopción consumada porque el requisito dice "adopción **o**
     * contacto", y este es el contacto: un match a favor abre el chat con la
     * protectora (RF-12), así que quien pasa por aquí ha tenido de verdad trato
     * con ella. Exigir la adopción dejaría sin voz a quien contactó y no adoptó,
     * que es justo la experiencia que más interesa conocer.
     *
     * Reutiliza la misma forma que las consultas del panel: un EXISTS contra
     * ANIMAL_ADOPCION, porque MATCH_REGISTRO guarda una referencia polimórfica sin
     * clave ajena y no se puede unir por asociación. El filtro por tipo no sobra:
     * sin él, deslizar el avistamiento 7 contaría como contacto con la protectora
     * dueña del animal 7, que no tiene nada que ver.
     */
    @Query("""
            SELECT COUNT(m) > 0 FROM MatchRegistro m
            WHERE m.usuario.id = :usuarioId
              AND m.tipoPublicacion = :adopcion
              AND m.interesado = true
              AND EXISTS (SELECT 1 FROM AnimalAdopcion a
                          WHERE a.id = m.publicacionId
                            AND a.protectora.id = :protectoraId)
            """)
    boolean existeContactoConProtectora(@Param("usuarioId") Long usuarioId,
                                        @Param("protectoraId") Long protectoraId,
                                        @Param("adopcion") TipoPublicacion adopcion);
}
