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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Valoración de 1 a 5 estrellas que un usuario emite sobre una protectora tras
 * una adopción o un contacto (RF-23).
 *
 * UNA POR USUARIO Y PROTECTORA. RF-23 habla de "la valoración" en singular:
 * volver a valorar es cambiar de opinión, no emitir un segundo voto. El service
 * busca la anterior y la actualiza, pero eso solo no basta —dos peticiones
 * simultáneas del mismo usuario leerían las dos "no existe" antes de que
 * ninguna insertara—, así que la restricción vive también en la tabla, creada
 * en la migración V9. Aquí se declara para que `validate` no vea una entidad
 * que promete menos que el esquema, aunque no llegue a comprobarla: `validate`
 * mira columnas, tipos y nulabilidad, nunca unicidad.
 *
 * NO ALIMENTA NINGUNA MEDIA GUARDADA. Protectora tenía una columna
 * valoracion_media que nadie mantenía; se eliminó en V9 y la media se calcula
 * al leerla (ver ValoracionService).
 */
@Entity
@Table(
        name = "valoracion",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_valoracion_usuario_protectora",
                columnNames = {"usuario_id", "protectora_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Valoracion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "protectora_id", nullable = false)
    private Protectora protectora;

    @Min(1)
    @Max(5)
    @Column(nullable = false)
    private Integer puntuacion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
