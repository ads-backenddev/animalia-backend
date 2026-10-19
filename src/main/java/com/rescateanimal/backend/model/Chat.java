package com.rescateanimal.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Conversación en tiempo real generada por un match (RF-12).
 * Relación 1:1 con MatchRegistro: cada match abre como máximo un chat.
 *
 * NO HAY TABLA DE PARTICIPANTES, Y ES DELIBERADO. Los dos extremos de la
 * conversación no son columnas de aquí: se deducen del match. Uno es quien
 * deslizó (MATCH_REGISTRO.usuario) y el otro quien publicó lo deslizado —el
 * autor del avistamiento (RF-08) o la cuenta de la protectora dueña del animal
 * (RF-09)—. Los resuelve ChatService.exigirParticipante, único punto de entrada
 * al chat, y lo cubren las pruebas del bloque 5.
 *
 * Una tabla CHAT_PARTICIPANTE es la forma habitual de modelar esto y aquí sería
 * un error: los participantes son siempre exactamente dos y no cambian nunca en
 * toda la vida del chat, mientras que una tabla de participantes puede
 * representar cero, uno o siete, y admite altas y bajas. Es decir, sabría
 * expresar estados que este dominio no admite, y haría falta defenderla con
 * restricciones adicionales para volver a prohibir lo que hoy es imposible por
 * construcción. Además duplicaría un dato que ya está en el match, con lo que
 * eso trae: dos fuentes para la misma verdad y la posibilidad de que discrepen.
 *
 * Esto cambiaría si algún día un chat admitiera más de dos personas o pudiera
 * cambiar de manos; mientras no ocurra, la ausencia de la tabla es la respuesta
 * correcta, no una pieza que falte.
 */
@Entity
@Table(name = "chat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false, unique = true)
    private MatchRegistro match;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
