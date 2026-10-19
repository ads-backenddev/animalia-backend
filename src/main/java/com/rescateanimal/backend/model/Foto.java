package com.rescateanimal.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Un archivo subido por POST /api/fotos y quién lo subió.
 *
 * Es lo que permite exigir que un fotoUrl sea del propio usuario, borrar la foto
 * de perfil en la anonimización (RF-19) y limpiar las subidas que nunca se
 * usaron. El razonamiento de cada columna, y de las que no están, en la
 * migración V14.
 *
 * LA CLAVE PRIMARIA ES EL NOMBRE DEL ARCHIVO, el mismo que lleva la URL. Quién
 * la usa no se guarda aquí: se consulta en los foto_url de las cuatro tablas
 * que tienen uno.
 */
@Entity
@Table(name = "foto", indexes = {
        // Para la limpieza diaria, que filtra por antigüedad. Existe en V14:
        // validate no comprueba índices.
        @Index(name = "idx_foto_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Foto implements Persistable<String> {

    /** "<uuid>.jpg" o "<uuid>.png". */
    @Id
    @Column(name = "nombre", nullable = false, length = 40)
    private String nombre;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    /** Bytes del archivo ya recodificado, que es lo que ocupa en disco. */
    @Column(name = "tamanyo", nullable = false)
    private Integer tamanyo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Si la fila está aún por insertar. Existe por la clave asignada: con un
     * nombre ya relleno, Spring Data da la entidad por existente y save() hace
     * merge, que es un SELECT antes de cada INSERT para descubrir lo que ya se
     * sabe —que el nombre acaba de generarse y no puede estar en la tabla—.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean pendienteDeInsertar = true;

    @Override
    public String getId() {
        return nombre;
    }

    @Override
    public boolean isNew() {
        return pendienteDeInsertar;
    }

    @PostLoad
    @PostPersist
    void marcarInsertada() {
        pendienteDeInsertar = false;
    }
}
