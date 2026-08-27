package com.rescateanimal.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Token invalidado antes de su caducidad natural (RF-16, RF-17).
 *
 * POR QUÉ ESTO ES UNA TABLA Y NO UN MAPA EN MEMORIA, que es lo que era hasta
 * ahora: una revocación guardada solo en memoria se pierde al reiniciar la
 * aplicación, y reiniciar no es un caso raro sino cada despliegue. El token del
 * que alguien hizo logout volvía a autenticar durante lo que le quedara de sus 24
 * horas. El escenario típico —cerrar sesión en un ordenador compartido y que la
 * sesión se reabra sola tras el siguiente deploy— es justo el que el logout
 * existe para evitar.
 *
 * LA CLAVE PRIMARIA ES EL jti, no un id autogenerado. Es ya un UUID único por
 * token y es exactamente lo que se consulta: darle un id propio obligaría a
 * mantener además un índice único sobre el jti para lo mismo, con una columna de
 * más y una consulta indirecta en el camino crítico de CADA petición
 * autenticada.
 *
 * NO SE GUARDA EL TOKEN ENTERO, solo su identificador. Un token completo es una
 * credencial utilizable; su jti no sirve para autenticarse, así que una fuga de
 * esta tabla no permite suplantar a nadie.
 *
 * La fila deja de hacer falta cuando el token caduca por sí solo: a partir de ese
 * momento lo rechaza la propia validación de la firma. Por eso se guarda
 * expiraEn, con la que TokenRevocadoService purga lo que ya no aporta nada.
 */
@Entity
@Table(name = "token_revocado", indexes = {
        // La purga borra por caducidad; sin este índice haría un recorrido
        // completo de la tabla cada vez.
        @Index(name = "idx_token_revocado_expira_en", columnList = "expira_en")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TokenRevocado {

    /** Claim "jti" del JWT: un UUID generado al emitirlo. */
    @Id
    @Column(name = "jti", nullable = false, length = 36)
    private String jti;

    /** Cuándo caduca el token por sí solo y esta fila deja de hacer falta. */
    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    /** Cuándo se revocó. No se usa para decidir nada; sirve para auditar. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
