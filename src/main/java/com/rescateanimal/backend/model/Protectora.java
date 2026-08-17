package com.rescateanimal.backend.model;

import com.rescateanimal.backend.model.enums.EstadoProtectora;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Datos de organización de un usuario con rol PROTECTORA (RF-01, RF-10).
 * Relación 1:1 con Usuario ("es"): la cuenta de acceso vive en Usuario y los
 * datos de la entidad rescatista aquí.
 */
@Entity
@Table(name = "protectora")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Protectora {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, unique = true)
    private Usuario usuario;

    @Column(name = "nombre_organizacion", nullable = false, length = 150)
    private String nombreOrganizacion;

    @Column(length = 250)
    private String direccion;

    /** Zona de actuación, usada para contactar con la protectora local (RF-06). */
    @Column(name = "zona_geografica", length = 150)
    private String zonaGeografica;

    /** Requiere verificación del administrador antes de pasar a ACTIVA (RF-11). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoProtectora estado;

    /** Media de las valoraciones recibidas, de 1 a 5 estrellas (RF-23). */
    @Column(name = "valoracion_media", precision = 3, scale = 2)
    private BigDecimal valoracionMedia;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
