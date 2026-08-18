package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
