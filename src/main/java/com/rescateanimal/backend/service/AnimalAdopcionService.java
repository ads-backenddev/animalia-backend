package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.ActualizarAnimalRequest;
import com.rescateanimal.backend.dto.request.CrearAnimalRequest;
import com.rescateanimal.backend.dto.response.AnimalAdopcionDetalleResponse;
import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.AnimalAjenoException;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.Tamanyo;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.CatalogoAdopcionSpec;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lógica del catálogo de adopción: publicación y gestión de animales por la
 * protectora, filtros y confirmación de adopción (RF-07, RF-20, RF-22).
 *
 * PROPIEDAD DE UN ANIMAL. El rol PROTECTORA lo comprueba @PreAuthorize en el
 * controller, pero "ser protectora" no basta para tocar un animal concreto:
 * hace falta ser LA protectora que lo publicó. Eso no puede decidirlo una
 * anotación —depende del animal que se pida— y se resuelve aquí, en
 * exigirPropietaria, que es el único sitio por el que pasan las tres
 * operaciones de escritura.
 */
@Slf4j
@Service
public class AnimalAdopcionService {

    /**
     * Tope de animales por página. Mismo motivo que en el histórico del chat:
     * sin él, un size enorme permitiría forzar respuestas de todo el catálogo
     * (RNF-02).
     */
    static final int MAXIMO_POR_PAGINA = 100;

    private final AnimalAdopcionRepository animalAdopcionRepository;
    private final ProtectoraRepository protectoraRepository;

    /** Tamaño de la tanda que devuelve el feed de swipe (RF-09). */
    private final int tamanyoFeed;

    public AnimalAdopcionService(AnimalAdopcionRepository animalAdopcionRepository,
                                 ProtectoraRepository protectoraRepository,
                                 @Value("${app.adopcion.tamanyo-feed}") int tamanyoFeed) {
        this.animalAdopcionRepository = animalAdopcionRepository;
        this.protectoraRepository = protectoraRepository;
        this.tamanyoFeed = tamanyoFeed;
    }

    // ---------------------------------------------------------------------
    // Gestión por la protectora (RF-07, RF-20)
    // ---------------------------------------------------------------------

    /**
     * Publica un animal en el catálogo (RF-07).
     *
     * La protectora sale del usuario autenticado, nunca del cuerpo: así nadie
     * puede publicar a nombre de otra organización. Nace DISPONIBLE porque un
     * animal que se publica es, por definición, un animal que se ofrece.
     */
    @Transactional
    public AnimalAdopcionResponse publicar(CrearAnimalRequest peticion, Long usuarioId) {
        Protectora protectora = protectoraDe(usuarioId);

        AnimalAdopcion animal = new AnimalAdopcion();
        animal.setProtectora(protectora);
        animal.setNombre(peticion.getNombre().trim());
        animal.setEspecie(peticion.getEspecie().trim());
        animal.setTamanyo(peticion.getTamanyo());
        animal.setDescripcion(peticion.getDescripcion().trim());
        animal.setFotoUrl(peticion.getFotoUrl().trim());
        animal.setZonaGeografica(peticion.getZonaGeografica().trim());
        animal.setEstado(EstadoAnimal.DISPONIBLE);

        AnimalAdopcion guardado = animalAdopcionRepository.save(animal);
        log.info("Animal {} publicado en adopción por la protectora {}",
                guardado.getId(), protectora.getId());

        return aResponse(guardado);
    }

    /**
     * Modifica un animal de la propia protectora (RF-07).
     *
     * Se puede editar un animal ya ADOPTADO o RETIRADO —corregir una
     * descripción o una foto— porque editar no cambia su disponibilidad. Lo que
     * no se puede es devolverlo al catálogo por esta vía: el estado no viaja en
     * el cuerpo.
     */
    @Transactional
    public AnimalAdopcionResponse actualizar(Long animalId, ActualizarAnimalRequest peticion,
                                             Long usuarioId) {

        AnimalAdopcion animal = exigirPropietaria(animalId, usuarioId);

        animal.setNombre(peticion.getNombre().trim());
        animal.setEspecie(peticion.getEspecie().trim());
        animal.setTamanyo(peticion.getTamanyo());
        animal.setDescripcion(peticion.getDescripcion().trim());
        animal.setFotoUrl(peticion.getFotoUrl().trim());
        animal.setZonaGeografica(peticion.getZonaGeografica().trim());

        log.info("Animal {} actualizado por la protectora del usuario {}", animalId, usuarioId);
        return aResponse(animal);
    }

    /**
     * Retira un animal del catálogo (RF-07): pasa a RETIRADO.
     *
     * NO borra la fila, y ese es el cambio. Un DELETE dejaba huérfanos los
     * MATCH_REGISTRO que apuntaran al animal —publicacion_id es una referencia
     * polimórfica, así que ninguna clave ajena lo impedía— y con ellos su chat
     * y sus mensajes. ChatService sabía esquivarlos, pero para el ciudadano al
     * otro lado eso significaba ver desaparecer una conversación en curso sin
     * poder releer siquiera lo ya hablado. Conservando la fila, el chat sigue
     * en pie y quien lo abrió ve que el animal está RETIRADO.
     *
     * Es idempotente: retirar dos veces deja el mismo estado. Y es terminal,
     * como ADOPTADO: no hay vuelta a DISPONIBLE por esta vía, por el mismo
     * motivo que en confirmarAdopcion —reabrir la ficha dejaría los matches del
     * ciclo anterior colgando de un caso que ya no es el mismo—. Un animal que
     * vuelve a estar disponible se publica de nuevo.
     */
    @Transactional
    public void retirar(Long animalId, Long usuarioId) {
        AnimalAdopcion animal = exigirPropietaria(animalId, usuarioId);

        if (animal.getEstado() == EstadoAnimal.RETIRADO) {
            log.debug("El animal {} ya estaba retirado; la operación no cambia nada", animalId);
            return;
        }

        animal.setEstado(EstadoAnimal.RETIRADO);
        log.info("Animal {} retirado del catálogo por el usuario {}", animalId, usuarioId);
    }

    /**
     * Confirma la adopción: el animal pasa a ADOPTADO (RF-20).
     *
     * Es idempotente —confirmar dos veces deja el mismo estado— y por eso el
     * endpoint es PUT y no PATCH: fija por completo el estado del animal, igual
     * que el marcado de leídos del chat.
     *
     * No admite la vuelta atrás a DISPONIBLE. Si un animal vuelve a estar
     * disponible tras una adopción fallida, lo que corresponde es publicarlo de
     * nuevo: reabrir la misma ficha dejaría los matches del ciclo anterior
     * colgando de un animal que ya no es el mismo caso.
     *
     * Tampoco desde RETIRADO. Antes ese caso no existía —retirar borraba la
     * fila y confirmar después daba 404— y al conservarla habría quedado un
     * camino nuevo, DISPONIBLE -> RETIRADO -> ADOPTADO, que afirma dos cosas
     * incompatibles: que el animal se sacó del catálogo y que se adoptó. Se
     * corta aquí con un 400 en vez de con un estado imposible de interpretar
     * después.
     */
    @Transactional
    public AnimalAdopcionResponse confirmarAdopcion(Long animalId, Long usuarioId) {
        AnimalAdopcion animal = exigirPropietaria(animalId, usuarioId);

        if (animal.getEstado() == EstadoAnimal.RETIRADO) {
            throw new OperacionNoPermitidaException("ANIMAL_RETIRADO",
                    "Este animal se retiró del catálogo; no puede confirmarse su adopción");
        }

        if (animal.getEstado() == EstadoAnimal.ADOPTADO) {
            log.debug("El animal {} ya estaba adoptado; la confirmación no cambia nada", animalId);
            return aResponse(animal);
        }

        animal.setEstado(EstadoAnimal.ADOPTADO);
        log.info("Animal {}: adopción confirmada por el usuario {}", animalId, usuarioId);

        return aResponse(animal);
    }

    // ---------------------------------------------------------------------
    // Consultas del catálogo (RF-22)
    // ---------------------------------------------------------------------

    /**
     * Catálogo paginado con los filtros de RF-22, combinables entre sí.
     *
     * Solo se listan los DISPONIBLE: un animal ya adoptado no es catálogo, y
     * mostrarlo llevaría a solicitudes sobre animales que ya tienen casa. Sigue
     * siendo accesible por su detalle, para quien llegue desde un enlace o desde
     * un chat abierto antes de la adopción.
     *
     * Eso mismo deja fuera a los RETIRADO sin necesidad de filtro nuevo: la
     * condición no es "los que no estén adoptados" sino "los que estén
     * DISPONIBLE", así que cualquier estado que se añada al enum nace excluido
     * del catálogo y del feed, que es el valor por defecto seguro.
     *
     * Cómo se comparan los filtros —y que uno en blanco equivale a no filtrar—
     * lo describe CatalogoAdopcionSpec. Aquí solo se decide lo que es política
     * del catálogo: que el estado no lo elige el cliente, y los topes de página.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<AnimalAdopcionResponse> catalogo(String especie, Tamanyo tamanyo,
                                                           String zona, Pageable paginacion) {

        PageRequest peticion = PageRequest.of(
                Math.max(0, paginacion.getPageNumber()),
                Math.min(Math.max(1, paginacion.getPageSize()), MAXIMO_POR_PAGINA),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<AnimalAdopcion> pagina = animalAdopcionRepository.findAll(
                CatalogoAdopcionSpec.de(EstadoAnimal.DISPONIBLE, especie, tamanyo, zona), peticion);

        return PaginaResponse.de(pagina, AnimalAdopcionService::aResponse);
    }

    /**
     * Todos los animales de una protectora, en cualquier estado (RF-10).
     *
     * ES EL MISMO CATÁLOGO, CON OTRA POLÍTICA, y por eso vive aquí y no en
     * ProtectoraPanelService: la forma de la consulta, el tope de página, el
     * orden y la conversión a DTO son los del catálogo público, y duplicarlos en
     * el panel habría creado una segunda copia que se separaría en cuanto
     * cualquiera de las dos cambiara. Lo único distinto es qué se enseña, que es
     * precisamente lo que decide este método.
     *
     * Y lo que enseña es TODO: DISPONIBLE, ADOPTADO y RETIRADO. El catálogo
     * público solo lista los DISPONIBLE porque es un escaparate; el panel es el
     * histórico de gestión de la protectora, donde un animal retirado sigue
     * siendo suyo y tiene que poder verlo. Es la diferencia deliberada entre los
     * dos listados.
     *
     * NO comprueba permisos, a propósito: recibe una protectora ya resuelta y ya
     * verificada por ProtectoraPanelService, que es el único punto de entrada al
     * panel. Nadie más debería llamarlo con un id que no venga de ahí.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<AnimalAdopcionResponse> listarDeLaProtectora(Long protectoraId,
                                                                       Pageable paginacion) {

        PageRequest peticion = PageRequest.of(
                Math.max(0, paginacion.getPageNumber()),
                Math.min(Math.max(1, paginacion.getPageSize()), MAXIMO_POR_PAGINA),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<AnimalAdopcion> pagina = animalAdopcionRepository.findAll(
                CatalogoAdopcionSpec.deLaProtectora(protectoraId), peticion);

        return PaginaResponse.de(pagina, AnimalAdopcionService::aResponse);
    }

    /**
     * Tarjetas para el swipe de adopción (RF-09, RF-15).
     *
     * Es el catálogo menos lo que este usuario no debe volver a ver: sus propios
     * animales, si es una protectora, y lo que ya deslizó en cualquiera de los
     * dos sentidos. Sin esa segunda exclusión, un descarte reaparecería en la
     * siguiente sesión y el segundo swipe acabaría en 409 por la restricción
     * única.
     *
     * Admite los mismos filtros que el catálogo: quien busca un perro pequeño en
     * su zona quiere deslizar sobre eso, no sobre todo el catálogo.
     *
     * Devuelve una tanda acotada y no una página, igual que el feed de
     * avistamientos: se consume tarjeta a tarjeta, así que ni el cliente pide
     * páginas ni le sirve saber cuántas hay. El orden es por fecha, lo más
     * reciente primero.
     */
    @Transactional(readOnly = true)
    public List<AnimalAdopcionResponse> feed(String especie, Tamanyo tamanyo, String zona,
                                             Long usuarioId) {

        return animalAdopcionRepository.findAll(
                        CatalogoAdopcionSpec.paraFeed(
                                EstadoAnimal.DISPONIBLE, especie, tamanyo, zona, usuarioId),
                        PageRequest.of(0, tamanyoFeed,
                                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .getContent().stream()
                .map(AnimalAdopcionService::aResponse)
                .toList();
    }

    /**
     * Detalle de un animal, con los datos públicos de su protectora.
     *
     * Sirve el animal en cualquier estado, también ADOPTADO y RETIRADO: quien
     * llega aquí lo hace desde un enlace o desde un chat abierto antes, y
     * merece ver qué pasó con el animal en lugar de un 404. El estado va en la
     * respuesta para que el cliente lo pinte como corresponda.
     */
    @Transactional(readOnly = true)
    public AnimalAdopcionDetalleResponse detalle(Long animalId) {
        AnimalAdopcion animal = animalAdopcionRepository.buscarConProtectora(animalId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El animal " + animalId + " no existe"));

        Protectora protectora = animal.getProtectora();

        return AnimalAdopcionDetalleResponse.builder()
                .id(animal.getId())
                .nombre(animal.getNombre())
                .especie(animal.getEspecie())
                .tamanyo(animal.getTamanyo())
                .descripcion(animal.getDescripcion())
                .fotoUrl(animal.getFotoUrl())
                .zonaGeografica(animal.getZonaGeografica())
                .estado(animal.getEstado().name())
                .createdAt(animal.getCreatedAt())
                .protectoraId(protectora.getId())
                .protectoraNombre(protectora.getNombreOrganizacion())
                .protectoraDireccion(protectora.getDireccion())
                .protectoraZonaGeografica(protectora.getZonaGeografica())
                .protectoraValoracionMedia(protectora.getValoracionMedia())
                .build();
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /**
     * Resuelve un animal y exige que lo publicara la protectora del usuario.
     *
     * El 404 va antes que el 403 solo cuando el animal no existe; existiendo, un
     * animal de otra protectora es 403 y no 404, igual que en el chat: el
     * recurso está ahí y lo que falla es el permiso. Los ids son secuenciales y
     * el catálogo es público entre autenticados, así que confirmar la existencia
     * no revela nada que no revele ya GET /api/adopcion/{id}.
     *
     * @throws ResourceNotFoundException si el animal no existe (404)
     * @throws AnimalAjenoException      si es de otra protectora (403)
     */
    private AnimalAdopcion exigirPropietaria(Long animalId, Long usuarioId) {
        AnimalAdopcion animal = animalAdopcionRepository.buscarConProtectora(animalId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El animal " + animalId + " no existe"));

        if (!animal.getProtectora().getUsuario().getId().equals(usuarioId)) {
            log.warn("El usuario {} ha intentado gestionar el animal {}, de otra protectora",
                    usuarioId, animalId);
            throw new AnimalAjenoException("Este animal pertenece a otra protectora");
        }
        return animal;
    }

    /**
     * La protectora del usuario autenticado.
     *
     * El 403 cubre un caso que @PreAuthorize no ve: una cuenta con rol
     * PROTECTORA cuya fila de PROTECTORA no existe. No debería ocurrir —el
     * registro las crea juntas—, pero de no comprobarlo saldría un 500 por un
     * Optional vacío en vez de un error entendible. Es exactamente el supuesto
     * de CuentaNoAutorizadaException: las credenciales valen, pero la cuenta no
     * puede operar.
     */
    private Protectora protectoraDe(Long usuarioId) {
        return protectoraRepository.findByUsuarioId(usuarioId)
                .orElseThrow(() -> new CuentaNoAutorizadaException("SIN_PROTECTORA",
                        "Tu cuenta no tiene una protectora asociada"));
    }


    private static AnimalAdopcionResponse aResponse(AnimalAdopcion animal) {
        Protectora protectora = animal.getProtectora();

        return AnimalAdopcionResponse.builder()
                .id(animal.getId())
                .nombre(animal.getNombre())
                .especie(animal.getEspecie())
                .tamanyo(animal.getTamanyo())
                .descripcion(animal.getDescripcion())
                .fotoUrl(animal.getFotoUrl())
                .zonaGeografica(animal.getZonaGeografica())
                .estado(animal.getEstado().name())
                .protectoraId(protectora.getId())
                .protectoraNombre(protectora.getNombreOrganizacion())
                .createdAt(animal.getCreatedAt())
                .build();
    }
}
