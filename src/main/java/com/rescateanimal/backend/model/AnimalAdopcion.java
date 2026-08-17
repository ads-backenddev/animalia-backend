package com.rescateanimal.backend.model;

import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.Tamanyo;
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
 * Animal publicado en el catálogo de adopción por una protectora (RF-07, RF-10).
 * Especie, tamaño y zona geográfica son los criterios de filtrado (RF-22).
 */
@Entity
@Table(name = "animal_adopcion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnimalAdopcion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "protectora_id", nullable = false)
    private Protectora protectora;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(nullable = false, length = 50)
    private String especie;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Tamanyo tamanyo;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "foto_url", length = 500)
    private String fotoUrl;

    @Column(name = "zona_geografica", length = 150)
    private String zonaGeografica;

    /** Pasa a ADOPTADO al confirmarse la adopción (RF-20). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoAnimal estado;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
