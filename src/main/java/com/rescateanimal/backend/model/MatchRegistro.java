package com.rescateanimal.backend.model;

import com.rescateanimal.backend.model.enums.TipoPublicacion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Registro del deslizamiento (swipe) de un usuario sobre una publicación, sea un
 * avistamiento (RF-08) o un animal en adopción (RF-09).
 *
 * El campo interesado distingue los dos sentidos del swipe: un match positivo,
 * que genera el chat de coordinación (RF-12) y, si es de adopción, consume cuota
 * diaria (RF-24), y un descarte, que solo sirve para no volver a mostrar la
 * publicación en el feed (RF-15). Un descarte de avistamiento puede convertirse
 * después en match: el mismo registro cambia de sentido.
 *
 * La restricción única (usuario_id, publicacion_id, tipo_publicacion) garantiza
 * un solo deslizamiento por usuario y publicación, de modo que el feed puede
 * excluir lo ya visto y no se pueden duplicar matches.
 *
 * POR QUÉ SIGUE HABIENDO UNA REFERENCIA POLIMÓRFICA (decisión tomada en el
 * bloque de mantenimiento del esquema, y revisada entonces a conciencia).
 *
 * publicacionId apunta a AVISTAMIENTO o a ANIMAL_ADOPCION según tipoPublicacion,
 * sin clave ajena que lo respalde. Es una debilidad conocida y tiene nombre: la
 * base de datos no puede impedir que apunte a una fila que no existe, y el id 7
 * existe en las dos tablas, así que cualquier consulta que lea publicacion_id
 * sin emparejarlo con el tipo puede leer la fila equivocada.
 *
 * Las dos alternativas se estudiaron y se descartaron para ahora:
 *
 * - ARCO EXCLUSIVO: dos columnas nulables con clave ajena cada una
 *   (avistamiento_id, animal_id) y un CHECK que exija exactamente una. Resuelve
 *   la integridad, pero obliga a tocar todas las consultas que hoy filtran por
 *   (publicacion_id, tipo) y no escala si algún día se desliza un tercer tipo.
 * - SUPERTIPO PUBLICACION: una tabla padre de la que hereden AVISTAMIENTO y
 *   ANIMAL_ADOPCION. Es la respuesta correcta del modelo relacional y la que se
 *   elegiría empezando de cero, pero implica reescribir las dos entidades, sus
 *   repositorios y la generación de ids.
 *
 * No se ha hecho ninguna de las dos porque no hay ningún requisito que lo exija
 * y el riesgo no está compensado: la suite entera depende de la forma actual de
 * AVISTAMIENTO y ANIMAL_ADOPCION, y la debilidad está contenida por dos cosas
 * que sí existen. La primera es que la referencia va SIEMPRE emparejada con el
 * tipo, sin excepción, en todos los sitios donde se lee: ChatRepository,
 * CatalogoAdopcionSpec, MatchService.resolverAutor, MatchRegistroRepository (las
 * búsquedas por usuario, publicación y tipo, la conversión de un descarte y las
 * consultas del panel de protectora), el feed y los descartados del dueño en
 * AvistamientoRepository y los destinatarios de ALERTA_ENCONTRADA en
 * UsuarioRepository. La segunda es que resolverAutor es un
 * switch exhaustivo sobre el enum, sin rama por defecto, de modo que añadir un
 * TipoPublicacion nuevo rompe la compilación en lugar de colarse en tiempo de
 * ejecución.
 *
 * Cada consulta nueva que lea publicacionId hereda esa obligación: emparejarlo
 * con el tipo. PanelProtectoraQueryTest la comprueba explícitamente para las del
 * panel, deslizando un avistamiento cuyo id coincide con el de un animal.
 *
 * Lo que hace falta para reabrir esto: un tercer tipo de publicación
 * deslizable, o un incidente real de integridad. Cualquiera de los dos inclina
 * la balanza hacia el supertipo.
 */
@Entity
@Table(
        name = "match_registro",
        uniqueConstraints = @UniqueConstraint(
                name = MatchRegistro.RESTRICCION_UN_DESLIZAMIENTO,
                columnNames = {"usuario_id", "publicacion_id", "tipo_publicacion"}
        ),
        // Para ir de una publicación a sus deslizamientos, a lo que no sirve la
        // restricción única porque empieza por usuario_id (V18).
        indexes = @Index(name = "idx_match_registro_publicacion", columnList = "publicacion_id, tipo_publicacion")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MatchRegistro {

    /**
     * Nombre de la restricción única, el mismo que le da V1. MatchService lo
     * reconoce cuando dos deslizamientos simultáneos de la misma publicación
     * chocan contra ella.
     */
    public static final String RESTRICCION_UN_DESLIZAMIENTO = "uk_match_usuario_publicacion";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    /**
     * Referencia polimórfica: según tipoPublicacion apunta al id de AVISTAMIENTO
     * o de ANIMAL_ADOPCION. Al poder apuntar a dos tablas distintas no puede
     * modelarse como @ManyToOne ni llevar clave ajena.
     */
    @Column(name = "publicacion_id", nullable = false)
    private Long publicacionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_publicacion", nullable = false, length = 20)
    private TipoPublicacion tipoPublicacion;

    /**
     * Sentido del deslizamiento: true si el usuario mostró interés (match, RF-08
     * y RF-09), false si descartó la publicación (RF-15). Solo los registros con
     * interesado = true abren chat, y de ellos solo los de adopción cuentan para
     * el límite diario: el dueño revisa los avistamientos de su alerta sin cupo.
     */
    @Column(nullable = false)
    private Boolean interesado;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
