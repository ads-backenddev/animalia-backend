package com.rescateanimal.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Mensaje individual dentro de un chat (RF-12).
 *
 * Se entrega en tiempo real por el destino STOMP /topic/chat/{chatId}, y queda
 * persistido para que el histórico siga estando disponible por REST cuando el
 * cliente no tenga la conexión abierta.
 */
@Entity
@Table(name = "mensaje", indexes = {
        // El histórico de un chat se lee siempre por chat_id y ordenado por
        // fecha; con esta pareja PostgreSQL resuelve filtro y orden en un solo
        // recorrido del índice, sin ordenación posterior. Es el índice que más
        // trabaja: es la consulta de cada apertura de conversación.
        @Index(name = "idx_mensaje_chat_created", columnList = "chat_id, created_at"),
        // Recuento de no leídos del listado de chats y el UPDATE de
        // /mensajes/leidos: los dos filtran por chat y por leido.
        @Index(name = "idx_mensaje_chat_leido", columnList = "chat_id, leido"),
        // PostgreSQL no indexa las claves ajenas por su cuenta, y esta hace
        // falta para el borrado RGPD del usuario (RF-19).
        @Index(name = "idx_mensaje_emisor", columnList = "emisor_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Mensaje {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "emisor_id", nullable = false)
    private Usuario emisor;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String contenido;

    @Column(nullable = false)
    private Boolean leido;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
