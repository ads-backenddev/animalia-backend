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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Avistamientos que un usuario lleva registrados en una alerta en un día
 * concreto.
 *
 * Es el tercer cupo con la forma de LimiteMatchDiario (RF-24) y
 * LimiteFotoDiario: al alcanzar el límite el registro responde 429, y el día
 * siguiente empieza con una fila nueva. Aquí la fila es por usuario, alerta y
 * día, porque lo que se limita es lo que una persona puede volcar sobre la
 * alerta de otra. El porqué, en la migración V19.
 */
@Entity
@Table(
        name = "limite_avistamiento_diario",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_limite_avistamiento_usuario_alerta_fecha",
                columnNames = {"usuario_id", "alerta_id", "fecha"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LimiteAvistamientoDiario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alerta_id", nullable = false)
    private Alerta alerta;

    /** Día natural de Europe/Madrid, no un instante. */
    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false)
    private Integer contador;
}
