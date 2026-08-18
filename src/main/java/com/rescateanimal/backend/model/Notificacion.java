package com.rescateanimal.backend.model;

import com.rescateanimal.backend.model.enums.TipoNotificacion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * Notificación dirigida a un usuario (RF-05).
 * Se entrega por el canal WebSocket /ws/notificaciones y queda persistida para
 * poder listarla y marcarla como leída.
 */
@Entity
@Table(name = "notificacion", indexes = {
        // Se consultan siempre por destinatario, y el badge de "sin leer" filtra
        // ademas por leida.
        @Index(name = "idx_notificacion_usuario_leida", columnList = "usuario_id, leida")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoNotificacion tipo;

    /** Id del elemento que origina la notificación; su tabla depende de tipo. */
    @Column(name = "referencia_id")
    private Long referenciaId;

    @Column(nullable = false)
    private Boolean leida;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
