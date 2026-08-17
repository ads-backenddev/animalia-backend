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
 * Contador de matches consumidos por un usuario en un día concreto (RF-24).
 * Al alcanzar el límite la API responde 429 y el contador se reinicia al día
 * siguiente con una fila nueva.
 *
 * La restricción única (usuario_id, fecha) garantiza una sola fila por usuario
 * y día, evitando contadores duplicados en peticiones concurrentes.
 */
@Entity
@Table(
        name = "limite_match_diario",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_limite_match_usuario_fecha",
                columnNames = {"usuario_id", "fecha"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LimiteMatchDiario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false)
    private Integer contador;
}
