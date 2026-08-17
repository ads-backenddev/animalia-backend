package com.rescateanimal.backend.model;

import com.rescateanimal.backend.model.enums.TipoPublicacion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Match realizado por un usuario sobre una publicación, sea un avistamiento
 * (RF-08) o un animal en adopción (RF-09). Genera el chat de coordinación (RF-12).
 */
@Entity
@Table(name = "match_registro")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MatchRegistro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    /**
     * Referencia polimórfica: según tipoPublicacion apunta al id de AVISTAMIENTO
     * o de ANIMAL_ADOPCION. Al poder apuntar a dos tablas distintas no puede
     * modelarse como @ManyToOne ni llevar clave ajena.
     */
    @Column(name = "publicacion_id", nullable = false)
    private Long publicacionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_publicacion", nullable = false, length = 20)
    private TipoPublicacion tipoPublicacion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
