package com.rescateanimal.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.locationtech.jts.geom.Point;

import java.time.LocalDateTime;

/**
 * Avistamiento de un animal registrado por un usuario sobre una alerta
 * existente (RF-21). Alimenta el feed de swipe y el sistema de match (RF-08).
 */
@Entity
@Table(name = "avistamiento")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Avistamiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alerta_id", nullable = false)
    private Alerta alerta;

    /** Foto del animal avistado, para la identificación visual del match (RF-21). */
    @Column(name = "foto_url", length = 500)
    private String fotoUrl;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point ubicacion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
