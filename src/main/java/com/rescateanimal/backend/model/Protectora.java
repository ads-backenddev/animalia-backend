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

import java.time.Instant;

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

    // NO HAY CAMPO valoracionMedia, y su ausencia es una decisión, no un olvido.
    // Existió como columna derivada y nadie la mantenía: ningún código la
    // escribía, así que era nula para todas las protectoras y lo habría seguido
    // siendo mientras se acumulaban valoraciones reales debajo. La media se
    // calcula donde se lee, con AVG y COUNT sobre VALORACION (ver
    // ValoracionService), de modo que no puede quedar desfasada. La columna se
    // eliminó en la migración V9.

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
