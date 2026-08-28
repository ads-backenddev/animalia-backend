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

    /**
     * Si detrás de la protectora hay una persona particular y no una entidad.
     *
     * LO DECLARA ELLA MISMA AL REGISTRARSE, y cambia qué pasa con estos datos
     * cuando su cuenta se da de baja (RF-19). En España es habitual que una
     * protectora pequeña sea alguien que rescata por su cuenta, sin estructura
     * legal, y que ponga su propio nombre como "organización" y su domicilio
     * particular como "dirección". Para esa persona, esas dos columnas son datos
     * personales tan identificables como su teléfono, y una baja que no los
     * tocara no borraría nada de lo que la identifica.
     *
     * Cuando es false —una asociación registrada— el nombre y la dirección son
     * datos de una entidad, no de una persona, y se conservan: son los que
     * explican de dónde salió cada animal publicado.
     *
     * NO SE PUEDE DEDUCIR, y por eso se pregunta. Nada en un nombre distingue con
     * fiabilidad "Huellas de Getafe" de "María Gómez Ruiz", y equivocarse tiene
     * coste en las dos direcciones: borrar de más deja el histórico sin
     * procedencia, borrar de menos deja expuesta a una persona.
     *
     * NACE EN false, igual que el DEFAULT de la columna. El valor inicial está
     * aquí y no solo en la base de datos porque una Protectora recién construida
     * tiene que ser válida antes de guardarse: sin él, cualquier código que
     * creara una sin acordarse del campo reventaría contra el NOT NULL en el
     * momento del INSERT, que es tarde y lejos de donde está el error. Y el valor
     * seguro es este: entre dejar un dato de más y borrar uno que hacía falta,
     * se deja el de más.
     */
    @Column(name = "es_persona_fisica", nullable = false)
    private Boolean esPersonaFisica = false;

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
