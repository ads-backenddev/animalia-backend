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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Registro del deslizamiento (swipe) de un usuario sobre una publicación, sea un
 * avistamiento (RF-08) o un animal en adopción (RF-09).
 *
 * El campo interesado distingue los dos sentidos del swipe: un match positivo,
 * que genera el chat de coordinación (RF-12) y consume cuota diaria (RF-24), y un
 * descarte, que solo sirve para no volver a mostrar la publicación en el feed
 * (RF-15).
 *
 * La restricción única (usuario_id, publicacion_id, tipo_publicacion) garantiza
 * un solo deslizamiento por usuario y publicación, de modo que el feed puede
 * excluir lo ya visto y no se pueden duplicar matches.
 */
@Entity
@Table(
        name = "match_registro",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_match_usuario_publicacion",
                columnNames = {"usuario_id", "publicacion_id", "tipo_publicacion"}
        )
)
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

    /**
     * Sentido del deslizamiento: true si el usuario mostró interés (match, RF-08
     * y RF-09), false si descartó la publicación (RF-15). Solo los registros con
     * interesado = true abren chat y cuentan para el límite diario.
     */
    @Column(nullable = false)
    private Boolean interesado;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
