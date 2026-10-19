package com.rescateanimal.backend.model;

import com.rescateanimal.backend.model.enums.Rol;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Usuario registrado en la plataforma (RF-01, RF-02).
 * Es la entidad raíz del modelo: alertas, avistamientos, matches, mensajes,
 * valoraciones, reportes y notificaciones cuelgan de ella.
 */
@Entity
@Table(name = "usuario")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nombre;

    /**
     * Identifica la cuenta y es unico SIN distinguir mayusculas.
     *
     * No lleva unique = true, y no es un descuido: @Column solo sabe declarar
     * una unicidad sobre la columna en crudo, y esa consideraria
     * "Ana@Test.es" y "ana@test.es" dos cuentas distintas, que es justo lo que
     * la aplicacion no admite —AuthService normaliza a minusculas antes de
     * registrar y antes de buscar—. La garantia la da el indice unico funcional
     * uk_usuario_email_lower sobre lower(email), creado en la migracion V5.
     */
    @Column(nullable = false, length = 150)
    private String email;

    /** Hash BCrypt de la contraseña, nunca la contraseña en claro (RNF-03). */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(length = 20)
    private String telefono;

    /** Área geográfica del usuario, usada para notificar alertas cercanas (RF-05). */
    @Column(name = "area_geografica", length = 150)
    private String areaGeografica;

    @Column(name = "foto_url", length = 500)
    private String fotoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Rol rol;

    /** Idioma seleccionado por el usuario para la aplicación (RF-18). */
    @Column(length = 10)
    private String idioma;

    /** Permite al administrador bloquear la cuenta sin borrarla (RF-11). */
    @Column(nullable = false)
    private Boolean activo;

    /**
     * Momento del último cambio de contraseña (RF-17).
     *
     * Actúa como corte de validez de los tokens: cualquier JWT emitido antes de
     * esta marca se rechaza. Así, restablecer la contraseña cierra de inmediato
     * todas las sesiones abiertas —el caso de uso real de RF-17 es una cuenta
     * comprometida— y un enlace de recuperación ya utilizado no vuelve a servir
     * aunque la aplicación se reinicie.
     */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
