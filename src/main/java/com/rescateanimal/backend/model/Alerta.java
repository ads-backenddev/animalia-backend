package com.rescateanimal.backend.model;

import com.rescateanimal.backend.model.enums.EstadoAlerta;
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
import org.locationtech.jts.geom.Point;

import java.time.LocalDateTime;

/**
 * Alerta de mascota perdida publicada por un usuario (RF-03).
 *
 * La ubicación es un Point de PostGIS (SRID 4326) para poder resolver por
 * consulta espacial tanto el mapa en tiempo real (RF-04) como la detección de
 * alertas duplicadas en un radio de 500 m en las últimas 24 h (RF-25).
 */
@Entity
@Table(name = "alerta")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Alerta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "foto_url", length = 500)
    private String fotoUrl;

    @Column(nullable = false, length = 50)
    private String especie;

    @Column(name = "fecha_avistamiento")
    private LocalDateTime fechaAvistamiento;

    /** Último lugar donde fue vista la mascota. */
    @Column(name = "ultima_ubicacion", columnDefinition = "geometry(Point,4326)")
    private Point ultimaUbicacion;

    /** Radio de búsqueda en metros alrededor de ultimaUbicacion. */
    @Column(name = "radio_busqueda")
    private Integer radioBusqueda;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoAlerta estado;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
