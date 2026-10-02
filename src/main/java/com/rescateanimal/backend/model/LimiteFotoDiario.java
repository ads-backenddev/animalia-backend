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
 * Subidas a POST /api/fotos que un usuario lleva en un día concreto.
 *
 * Es el gemelo de LimiteMatchDiario (RF-24): al alcanzar el límite la subida
 * responde 429, y el día siguiente empieza con una fila nueva. Por qué una tabla
 * aparte y no la de matches ampliada, en la migración V15.
 */
@Entity
@Table(
        name = "limite_foto_diario",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_limite_foto_usuario_fecha",
                columnNames = {"usuario_id", "fecha"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LimiteFotoDiario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    /** Día natural de Europe/Madrid, no un instante. */
    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false)
    private Integer contador;
}
