package com.rescateanimal.backend.model;

import com.rescateanimal.backend.model.enums.EstadoReporte;
import com.rescateanimal.backend.model.enums.MotivoReporte;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.model.enums.TipoObjetivo;
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
 * Reporte de una publicación o de un usuario por incumplir las normas (RF-26).
 * El administrador los revisa desde /api/admin/reportes (RF-11).
 */
@Entity
@Table(name = "reporte")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Reporte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Usuario que emite el reporte. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_objetivo", nullable = false, length = 20)
    private TipoObjetivo tipoObjetivo;

    /**
     * Referencia polimórfica: según tipoObjetivo apunta al id de una publicación
     * o de un usuario. Al poder apuntar a varias tablas no lleva clave ajena.
     */
    @Column(name = "objetivo_id", nullable = false)
    private Long objetivoId;

    /**
     * Discriminador de la tabla a la que apunta objetivoId cuando tipoObjetivo es
     * PUBLICACION, ya que hay tres tipos de publicación y el id solo es único
     * dentro de cada tabla. Nulo cuando tipoObjetivo es USUARIO.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_contenido", length = 20)
    private TipoContenido tipoContenido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MotivoReporte motivo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoReporte estado;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
