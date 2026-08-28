package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.ContactoProtectoraResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ProtectoraDetalleResponse;
import com.rescateanimal.backend.dto.response.ProtectoraDirectorioResponse;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Directorio público de protectoras: listado y ficha (RF-06).
 *
 * QUÉ ES ESTO Y QUÉ NO. Es lo que un ciudadano ve DE una protectora. El panel de
 * gestión —lo que una protectora ve DE SÍ MISMA— es ProtectoraPanelService, y
 * emitir una valoración es ValoracionService. Los tres tocan la misma tabla y
 * tienen reglas de acceso que no se parecen, que es por lo que están separados.
 *
 * =====================================================================
 * QUIÉN APARECE EN EL DIRECTORIO: DOS CONDICIONES, NO UNA.
 * =====================================================================
 *
 * La primera la pide RF-06 y es evidente: solo las ACTIVA. Una protectora
 * PENDIENTE todavía no ha sido verificada por el administrador y una RECHAZADA no
 * va a serlo (RF-11); publicarlas sería avalar ante el ciudadano a alguien de
 * quien la plataforma no responde. No aparecen en el listado y su ficha da 404,
 * también pidiéndola por su id.
 *
 * La segunda apareció construyendo esto y no la cubre el estado: LA CUENTA TIENE
 * QUE ESTAR ACTIVA. Una protectora que se da de baja (RF-19) conserva su
 * {@code estado = ACTIVA}, porque la baja anonimiza al usuario y retira sus
 * animales pero no toca la fila de PROTECTORA; una bloqueada por el
 * administrador (RF-11) tampoco lo cambia. Sin comprobar {@code usuario.activo},
 * el directorio habría publicado la ficha de una protectora que ya no existe y
 * habría ofrecido como forma de contactarla su email anonimizado, que apunta a
 * un dominio reservado que nunca puede existir. Las dos condiciones van juntas en
 * la consulta y en la ficha.
 *
 * =====================================================================
 * EL FILTRO POR ESTADO ES UN SWITCH, Y HAY UNO SOLO.
 * =====================================================================
 *
 * El listado necesita una condición SQL y la ficha una comprobación en Java: son
 * dos formas de preguntar lo mismo y podrían separarse con toda facilidad —basta
 * con que alguien añada un estado y se acuerde de uno de los dos sitios—. Aquí
 * hay una única fuente, el switch exhaustivo de esVisibleEnElDirectorio, y la
 * lista de estados que va a la consulta se DERIVA de él recorriendo el enum. Un
 * valor nuevo en EstadoProtectora rompe la compilación en ese switch, que es
 * exactamente lo que se busca: nada entra en el directorio por omisión.
 *
 * Es la misma disciplina de ProtectoraPanelService.exigirProtectoraActiva y de
 * MatchService.resolverAutor, y por el mismo motivo.
 *
 * =====================================================================
 * LAS VALORACIONES NO SE CALCULAN AQUÍ.
 * =====================================================================
 *
 * La media y el recuento los da ValoracionService, que es quien los calcula para
 * el detalle de un animal y para el panel. No es solo evitar repetir un AVG: es
 * que RF-06 y RF-07 enseñan la reputación de la misma protectora, y calcularla
 * por separado en dos sitios es la forma de que un día no coincidan. Hay una
 * prueba que pide los dos endpoints seguidos y compara los dos números.
 *
 * Los animales de la ficha vienen de AnimalAdopcionService por lo mismo: son el
 * catálogo con otra política de visibilidad, no un listado nuevo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProtectoraService {

    /**
     * Tope de protectoras por página, igual que en el catálogo de adopción y por
     * el mismo motivo: sin él, un size enorme permitiría forzar la respuesta con
     * el directorio entero (RNF-02). Y aquí importa algo más que allí, porque
     * cada fila lleva un dato de contacto.
     */
    static final int MAXIMO_POR_PAGINA = 100;

    /**
     * Cuántas valoraciones acompañan a la ficha pública.
     *
     * La ficha se sirve en cada visita al directorio, así que devolver el
     * histórico entero de una protectora con años de actividad sería trabajo
     * tirado para pintar las primeras. El número real de valoraciones viaja
     * aparte, en totalValoraciones, que sale de un COUNT y no de contar esta
     * lista: el cliente puede decir "10 de 137". Quien las quiere todas es la
     * propia protectora, y para eso está /panel/valoraciones (RF-10).
     */
    static final int VALORACIONES_EN_LA_FICHA = 10;

    /** Patrón LIKE que no filtra nada. Ver patronDeZona. */
    private static final String SIN_FILTRO_DE_ZONA = "%";

    /**
     * Los estados con los que una protectora se publica, derivados del switch.
     *
     * NO ES UNA LISTA ESCRITA A MANO, y esa es la gracia: sale de recorrer el
     * enum preguntándole a esVisibleEnElDirectorio, de modo que la condición del
     * listado y la de la ficha son literalmente la misma decisión. Añadir un
     * estado a EstadoProtectora no puede dejar esta lista desactualizada porque
     * no hay nada que actualizar: rompe la compilación en el switch y hasta que
     * alguien decida qué hacer con él, no compila nada.
     */
    private static final List<EstadoProtectora> ESTADOS_VISIBLES =
            Arrays.stream(EstadoProtectora.values())
                    .filter(ProtectoraService::esVisibleEnElDirectorio)
                    .toList();

    private final ProtectoraRepository protectoraRepository;
    private final ValoracionService valoracionService;
    private final AnimalAdopcionService animalAdopcionService;

    // ---------------------------------------------------------------------
    // Listado (RF-06)
    // ---------------------------------------------------------------------

    /**
     * Directorio paginado de protectoras publicables, con su nota y su contacto.
     *
     * ORDENADO POR NOMBRE, que es una excepción deliberada al orden por defecto
     * de esta API. El resto de listados van por fecha descendente porque son
     * "lo último que ha pasado"; un directorio no es eso, es una lista de
     * referencia que se recorre buscando a alguien concreto. Y con fecha
     * descendente las protectoras veteranas —las que más animales y más
     * valoraciones tienen— quedarían en la última página para siempre, que es
     * justo lo contrario de lo que un directorio debe hacer. Se ordena por
     * lower(nombre) para que el resultado no dependa de la intercalación de la
     * base de datos, con el id como desempate para que dos páginas seguidas no
     * repitan ni se salten filas.
     *
     * TRES CONSULTAS FIJAS, NO CRECEN CON LA PÁGINA: la de protectoras, su
     * recuento, y una sola agregación para las notas de todas ellas. Resolver la
     * media dentro del bucle habría dado dos consultas por fila —cuarenta en una
     * página de veinte—, que es el N+1 que ya se evitó en el panel y en
     * /api/matches/mios.
     *
     * @param zona filtro opcional por zona de actuación; se busca por contenido
     */
    @Transactional(readOnly = true)
    public PaginaResponse<ProtectoraDirectorioResponse> directorio(String zona,
                                                                   Pageable paginacion) {

        // Sin Sort: el orden lo fija la consulta, que ordena por lower(nombre) para
        // no depender de la intercalación y poder apoyarse en el índice de V13.
        PageRequest peticion = PageRequest.of(
                Math.max(0, paginacion.getPageNumber()),
                Math.min(Math.max(1, paginacion.getPageSize()), MAXIMO_POR_PAGINA));

        Page<Protectora> pagina = protectoraRepository.buscarParaDirectorio(
                ESTADOS_VISIBLES, patronDeZona(zona), peticion);

        Map<Long, ValoracionService.ResumenValoraciones> notas = valoracionService.resumenDeVarias(
                pagina.getContent().stream().map(Protectora::getId).toList());

        return PaginaResponse.de(pagina,
                protectora -> aDirectorioResponse(protectora, notas.get(protectora.getId())));
    }

    // ---------------------------------------------------------------------
    // Ficha (RF-06)
    // ---------------------------------------------------------------------

    /**
     * Ficha de una protectora con sus animales disponibles y sus valoraciones.
     *
     * DA 404 Y NO 403 CUANDO NO ES VISIBLE, y es una excepción consciente al
     * criterio del proyecto. En el chat y en el catálogo, pedir un recurso ajeno
     * que existe devuelve 403 y no 404, porque su existencia ya es pública y
     * fingir que no está no protege nada. Aquí es al revés: que una protectora
     * haya solicitado el alta y esté pendiente de verificación, o que se la haya
     * rechazado, es precisamente lo que no debe poder averiguarse recorriendo
     * ids. Un 403 confirmaría la fila; el 404 la deja indistinguible de un id que
     * nunca existió. El motivo real queda en el log, no en la respuesta.
     *
     * Vale igual para una cuenta dada de baja o bloqueada: la protectora deja de
     * ser accesible por id, no solo de aparecer en el listado. Si no, bastaría
     * con haber guardado el enlace antes.
     *
     * LOS ANIMALES SON LO ÚNICO QUE PAGINA de esta respuesta, y por eso el
     * Pageable del endpoint es suyo: el resto de la ficha tiene tamaño acotado
     * —las valoraciones llevan tope propio— y ellos son lo único que puede crecer
     * sin límite.
     *
     * @throws ResourceNotFoundException si no existe o no es visible (404)
     */
    @Transactional(readOnly = true)
    public ProtectoraDetalleResponse detalle(Long protectoraId, Pageable paginacionAnimales) {
        Protectora protectora = exigirVisible(protectoraId);

        ValoracionService.ResumenValoraciones nota =
                valoracionService.resumenDe(protectora.getId());

        return ProtectoraDetalleResponse.builder()
                .id(protectora.getId())
                .nombreOrganizacion(protectora.getNombreOrganizacion())
                .zonaGeografica(protectora.getZonaGeografica())
                .direccion(direccionPublicaDe(protectora))
                .esPersonaFisica(esPersonaFisica(protectora))
                .createdAt(protectora.getCreatedAt())
                .contacto(contactoDe(protectora))
                .valoracionMedia(nota.media())
                .totalValoraciones(nota.total())
                .animales(animalAdopcionService.listarDisponiblesDeLaProtectora(
                        protectora.getId(), paginacionAnimales))
                .valoraciones(valoracionService.ultimasDe(
                        protectora.getId(), VALORACIONES_EN_LA_FICHA))
                .build();
    }

    // ---------------------------------------------------------------------
    // Visibilidad
    // ---------------------------------------------------------------------

    /**
     * Si una protectora en ese estado se publica en el directorio.
     *
     * ES UN SWITCH-EXPRESIÓN SIN RAMA POR DEFECTO, y no un {@code == ACTIVA} ni
     * una lista de estados excluidos, por lo mismo que en
     * ProtectoraPanelService.exigirProtectoraActiva. Con una comparación o con
     * una lista negra, un estado nuevo en EstadoProtectora —"suspendida", "en
     * revisión de documentación"— se colaría en el directorio o se quedaría fuera
     * de él sin que nadie lo hubiera decidido, según cuál de las dos formas se
     * hubiera elegido. Escrito así, añadirlo no compila hasta que alguien diga
     * qué hacer con él.
     *
     * Y ES EL ÚNICO SITIO donde se decide: el listado no repite la condición,
     * deriva de aquí su lista de estados (ver ESTADOS_VISIBLES).
     *
     * OJO: esto NO es toda la visibilidad. La cuenta tiene que estar además
     * activa, cosa que no depende del estado de la protectora sino de la fila de
     * USUARIO. Ver exigirVisible y el javadoc de la clase.
     */
    private static boolean esVisibleEnElDirectorio(EstadoProtectora estado) {
        return switch (estado) {
            case ACTIVA -> true;
            case PENDIENTE -> false;
            case RECHAZADA -> false;
        };
    }

    /**
     * La protectora si se publica; 404 en cualquier otro caso.
     *
     * Las tres salidas dicen lo mismo al cliente —no existe— y cosas distintas al
     * log, que es donde tiene sentido distinguirlas para depurar sin que la
     * respuesta filtre nada.
     */
    private Protectora exigirVisible(Long protectoraId) {
        Protectora protectora = protectoraRepository.buscarConCuenta(protectoraId)
                .orElseThrow(() -> noExiste(protectoraId));

        if (!esVisibleEnElDirectorio(protectora.getEstado())) {
            log.debug("Ficha pedida de la protectora {}, que está en estado {}",
                    protectoraId, protectora.getEstado());
            throw noExiste(protectoraId);
        }

        if (!Boolean.TRUE.equals(protectora.getUsuario().getActivo())) {
            log.debug("Ficha pedida de la protectora {}, cuya cuenta no está activa", protectoraId);
            throw noExiste(protectoraId);
        }

        return protectora;
    }

    private static ResourceNotFoundException noExiste(Long protectoraId) {
        return new ResourceNotFoundException("La protectora " + protectoraId + " no existe");
    }

    // ---------------------------------------------------------------------
    // Conversión a DTO
    // ---------------------------------------------------------------------

    private static ProtectoraDirectorioResponse aDirectorioResponse(
            Protectora protectora, ValoracionService.ResumenValoraciones nota) {

        return ProtectoraDirectorioResponse.builder()
                .id(protectora.getId())
                .nombreOrganizacion(protectora.getNombreOrganizacion())
                .zonaGeografica(protectora.getZonaGeografica())
                .esPersonaFisica(esPersonaFisica(protectora))
                .valoracionMedia(nota.media())
                .totalValoraciones(nota.total())
                .contacto(contactoDe(protectora))
                .build();
    }

    /**
     * El contacto público de una protectora, que es lo que RF-06 pide.
     *
     * EL EMAIL SIEMPRE, EL TELÉFONO SOLO SI DETRÁS HAY UNA ENTIDAD. El
     * razonamiento entero —por qué aquí sí salen datos de contacto cuando en el
     * resto de la API no salen nunca, y por qué la línea se traza entre los dos
     * canales— está en ContactoProtectoraResponse, que es donde vive la decisión.
     *
     * Aquí solo queda dicho lo que hace: un teléfono nulo se omite del JSON, así
     * que "es un particular" y "no dio teléfono" se ven igual desde fuera.
     */
    private static ContactoProtectoraResponse contactoDe(Protectora protectora) {
        Usuario cuenta = protectora.getUsuario();

        return ContactoProtectoraResponse.builder()
                .email(cuenta.getEmail())
                .telefono(esPersonaFisica(protectora) ? null : cuenta.getTelefono())
                .build();
    }

    /**
     * La dirección, salvo que sea el domicilio de un particular.
     *
     * MISMO CRITERIO QUE EL TELÉFONO Y CON MÁS MOTIVO. V12 decidió que para una
     * persona física el nombre y la dirección son datos personales y que la baja
     * de cuenta los borra (RF-19); sería incoherente tratarlos como tales para
     * borrarlos y no tratarlos como tales para publicarlos.
     *
     * QUEDA UN AGUJERO FUERA DEL ALCANCE DE ESTE BLOQUE: GET /api/adopcion/{id}
     * devuelve protectoraDireccion sin mirar esPersonaFisica, así que la
     * dirección de un particular que tenga aunque sea un animal publicado sigue
     * siendo legible por ahí. Es contrato de un endpoint anterior; está anotado
     * en docs/api-rest.md §20 con el arreglo, que es una línea.
     */
    private static String direccionPublicaDe(Protectora protectora) {
        return esPersonaFisica(protectora) ? null : protectora.getDireccion();
    }

    /**
     * Si detrás de la protectora hay un particular.
     *
     * Con Boolean.TRUE.equals y no con un desempaquetado directo, que sobre un
     * nulo daría NullPointerException. La columna es NOT NULL desde V12 y la
     * entidad nace en false, así que en producción no puede serlo; el que puede
     * llegar nulo es un objeto construido a mano en una prueba.
     *
     * UN NULO SE LEE COMO "NO ES UN PARTICULAR", que es la lectura menos
     * protectora de las dos y aun así la correcta aquí, por dos motivos.
     * UsuarioService.anonimizarSiEsPersonaFisica hace exactamente lo mismo con el
     * mismo campo, y tener dos lecturas distintas de un nulo en el mismo dato
     * sería peor que cualquiera de las dos; y este proyecto ya eligió ese valor
     * por defecto a conciencia en V12 —entre dejar un dato de más y borrar uno
     * que hacía falta, se deja el de más— y esa decisión no se reabre aquí de
     * tapadillo.
     *
     * Lo que sí protege de verdad, y no depende de este nulo, es que las
     * protectoras anteriores a V12 quedaron en false: si alguna era una persona
     * física, su teléfono se publica. No hay forma retroactiva de saberlo; está
     * anotado como limitación conocida en docs/api-rest.md §20.
     */
    private static boolean esPersonaFisica(Protectora protectora) {
        return Boolean.TRUE.equals(protectora.getEsPersonaFisica());
    }

    /**
     * Convierte el filtro de zona en un patrón LIKE que nunca es nulo.
     *
     * ES LA LECCIÓN DE CatalogoAdopcionSpec, aplicada aquí porque esta consulta
     * es JPQL y no una Specification: un parámetro nulo no tiene tipo, y
     * comparar lower() contra él hace que PostgreSQL lo tome por bytea y falle
     * con "function lower(bytea) does not exist". Enviando siempre una cadena
     * —"%" cuando no se filtra— no hay ningún nulo que enlazar y la consulta es
     * una sola, sin ramas ni segunda versión que mantener.
     *
     * Un filtro en blanco es un filtro ausente, igual que en el catálogo:
     * "?zona=" significa "sin filtrar", no "las que no tengan zona".
     *
     * NO SE ESCAPAN LOS COMODINES de LIKE, y es a propósito: GET /api/adopcion
     * tiene el mismo parámetro con el mismo nombre y tampoco los escapa, así que
     * escaparlos aquí haría que dos filtros que el cliente ve iguales se
     * comportaran distinto. No abre nada: un "%" solo devuelve lo que ya
     * devuelve omitir el filtro.
     */
    private static String patronDeZona(String zona) {
        if (zona == null || zona.isBlank()) {
            return SIN_FILTRO_DE_ZONA;
        }
        return "%" + zona.trim().toLowerCase() + "%";
    }
}
