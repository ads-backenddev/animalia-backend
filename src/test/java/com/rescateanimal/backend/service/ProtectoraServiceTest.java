package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ProtectoraDetalleResponse;
import com.rescateanimal.backend.dto.response.ProtectoraDirectorioResponse;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del directorio público de protectoras (RF-06).
 *
 * DOS COSAS SE COMPRUEBAN AQUÍ POR ENCIMA DEL RESTO, y las dos son sobre lo que
 * NO debe salir:
 *
 * 1. QUIÉN APARECE. Una protectora PENDIENTE o RECHAZADA no está verificada, y
 *    publicarla sería avalar ante el ciudadano a alguien de quien la plataforma
 *    no responde. Ni en el listado ni pidiendo su ficha por id, que es la puerta
 *    de atrás fácil de olvidar. Y con ellas, las cuentas dadas de baja o
 *    bloqueadas, que conservan estado ACTIVA y no las cubre el estado.
 *
 * 2. QUÉ SE ENSEÑA DE CADA UNA. Este es el único punto de la API por el que
 *    salen el email y el teléfono de una cuenta, así que conviene fijar con
 *    pruebas exactamente cuándo sale cada uno: el email siempre, el teléfono y
 *    la dirección solo cuando detrás hay una entidad y no una persona.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProtectoraServiceTest {

    private static final Long ID_PROTECTORA = 30L;
    private static final Long ID_CUENTA = 4L;
    private static final String EMAIL = "contacto@huellas.es";
    private static final String TELEFONO = "600111222";
    private static final String DIRECCION = "Calle Mayor 3";

    @Mock
    private ProtectoraRepository protectoraRepository;
    @Mock
    private ValoracionService valoracionService;
    @Mock
    private AnimalAdopcionService animalAdopcionService;

    private ProtectoraService protectoraService;

    @BeforeEach
    void prepararServicio() {
        protectoraService = new ProtectoraService(
                protectoraRepository, valoracionService, animalAdopcionService);

        when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                .thenReturn(Optional.of(protectora(EstadoProtectora.ACTIVA, false)));
        when(protectoraRepository.buscarParaDirectorio(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(protectora(EstadoProtectora.ACTIVA, false))));

        when(valoracionService.resumenDe(anyLong()))
                .thenReturn(new ValoracionService.ResumenValoraciones(null, 0L));
        when(valoracionService.resumenDeVarias(any(Collection.class)))
                .thenReturn(Map.of(ID_PROTECTORA,
                        new ValoracionService.ResumenValoraciones(null, 0L)));
        when(valoracionService.ultimasDe(anyLong(), anyInt())).thenReturn(List.of());

        when(animalAdopcionService.listarDisponiblesDeLaProtectora(anyLong(), any()))
                .thenReturn(PaginaResponse.de(
                        new PageImpl<AnimalAdopcionResponse>(List.of()), animal -> animal));
    }

    // =====================================================================

    @Nested
    @DisplayName("Quién se publica en el directorio")
    class Visibilidad {

        @Test
        @DisplayName("El listado solo pide los estados que el switch declara visibles")
        void elListadoSoloPideLosEstadosVisibles() {
            protectoraService.directorio(null, Pageable.ofSize(20));

            assertThat(estadosPedidos()).containsExactly(EstadoProtectora.ACTIVA);
        }

        @ParameterizedTest(name = "estado {0}")
        @EnumSource(value = EstadoProtectora.class, names = {"PENDIENTE", "RECHAZADA"})
        @DisplayName("La ficha de una protectora sin verificar no existe, ni pidiéndola por su id")
        void laFichaDeUnaNoVerificadaDaNotFound(EstadoProtectora estado) {
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora(estado, false)));

            assertThatThrownBy(() -> protectoraService.detalle(ID_PROTECTORA, Pageable.ofSize(20)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        /**
         * ESTA PRUEBA ES LA QUE ATA LOS DOS ENDPOINTS. El listado filtra con una
         * condición SQL y la ficha con una comprobación en Java, y podrían
         * separarse sin que nada avisara. Aquí se recorre el enum entero
         * preguntándole a la ficha por cada estado, y se exige que el conjunto de
         * los que responden coincida exactamente con el que el listado pide a la
         * base de datos. Si algún día alguien añade un estado y lo mete solo en
         * uno de los dos sitios, esto falla.
         */
        @Test
        @DisplayName("Los estados del listado son exactamente los que la ficha deja pasar")
        void elListadoYLaFichaCoincidenEstadoAEstado() {
            protectoraService.directorio(null, Pageable.ofSize(20));
            List<EstadoProtectora> segunElListado = estadosPedidos();

            List<EstadoProtectora> segunLaFicha = Arrays.stream(EstadoProtectora.values())
                    .filter(ProtectoraServiceTest.this::laFichaResponde)
                    .toList();

            assertThat(segunLaFicha).containsExactlyElementsOf(segunElListado);
        }

        @Test
        @DisplayName("Una protectora que no existe da 404")
        void laProtectoraInexistenteDaNotFound() {
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> protectoraService.detalle(ID_PROTECTORA, Pageable.ofSize(20)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        /**
         * El caso que el estado NO cubre: la baja de cuenta (RF-19) anonimiza al
         * usuario y retira sus animales, pero deja la fila de PROTECTORA en
         * ACTIVA. Sin mirar activo, el directorio publicaría su ficha ofreciendo
         * como contacto el email anonimizado. Vale igual para una cuenta
         * bloqueada por el administrador (RF-11).
         */
        @Test
        @DisplayName("Una protectora con la cuenta inhabilitada no tiene ficha, aunque siga ACTIVA")
        void laCuentaInhabilitadaNoTieneFicha() {
            Protectora deBaja = protectora(EstadoProtectora.ACTIVA, false);
            deBaja.getUsuario().setActivo(false);
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(deBaja));

            assertThatThrownBy(() -> protectoraService.detalle(ID_PROTECTORA, Pageable.ofSize(20)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        /**
         * Un 403 confirmaría que la fila está ahí, que es justo lo que no debe
         * poder averiguarse recorriendo ids. Las dos respuestas tienen que ser
         * indistinguibles desde fuera.
         */
        @Test
        @DisplayName("El 404 de una pendiente no se distingue del de un id que nunca existió")
        void losDosCuatrocientosCuatroDicenLoMismo() {
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora(EstadoProtectora.PENDIENTE, false)));
            String deLaPendiente = mensajeDelNotFound();

            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA)).thenReturn(Optional.empty());
            String deLaInexistente = mensajeDelNotFound();

            assertThat(deLaPendiente).isEqualTo(deLaInexistente);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("Qué contacto se expone, y de quién")
    class Contacto {

        @Test
        @DisplayName("El email de la cuenta sale siempre: es lo que RF-06 pide")
        void elEmailSaleSiempre() {
            assertThat(primeraDelListado().getContacto().getEmail()).isEqualTo(EMAIL);
            assertThat(ficha().getContacto().getEmail()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("El teléfono de una entidad sale")
        void elTelefonoDeUnaEntidadSale() {
            assertThat(primeraDelListado().getContacto().getTelefono()).isEqualTo(TELEFONO);
            assertThat(ficha().getContacto().getTelefono()).isEqualTo(TELEFONO);
        }

        /**
         * El caso de V12: detrás de la protectora hay un particular, y ese
         * teléfono es su móvil personal. Un canal directo e inmediato que además
         * es opcional en el modelo, así que pudo rellenarlo para que el
         * administrador lo verificara (RF-11), no para un listado público.
         */
        @Test
        @DisplayName("El teléfono de una persona física no sale, ni en el listado ni en la ficha")
        void elTelefonoDeUnaPersonaFisicaNoSale() {
            conPersonaFisica();

            assertThat(primeraDelListado().getContacto().getTelefono()).isNull();
            assertThat(ficha().getContacto().getTelefono()).isNull();
        }

        @Test
        @DisplayName("Pero su email sí, o el directorio no serviría para nada")
        void elEmailDeUnaPersonaFisicaSiSale() {
            conPersonaFisica();

            assertThat(primeraDelListado().getContacto().getEmail()).isEqualTo(EMAIL);
            assertThat(ficha().getContacto().getEmail()).isEqualTo(EMAIL);
        }

        /**
         * V12 decidió que para una persona física la "dirección" es su domicilio
         * y que la baja de cuenta lo borra. Sería incoherente tratarlo como dato
         * personal para borrarlo y no tratarlo como tal para publicarlo.
         */
        @Test
        @DisplayName("La dirección de una persona física tampoco sale en su ficha")
        void laDireccionDeUnaPersonaFisicaNoSale() {
            conPersonaFisica();

            assertThat(ficha().getDireccion()).isNull();
        }

        @Test
        @DisplayName("La de una entidad sí: es donde está la protectora")
        void laDireccionDeUnaEntidadSale() {
            assertThat(ficha().getDireccion()).isEqualTo(DIRECCION);
        }

        @Test
        @DisplayName("Un teléfono que nadie rellenó no se inventa")
        void elTelefonoAusenteSigueAusente() {
            Protectora sinTelefono = protectora(EstadoProtectora.ACTIVA, false);
            sinTelefono.getUsuario().setTelefono(null);
            devolver(sinTelefono);

            assertThat(ficha().getContacto().getTelefono()).isNull();
        }

        /**
         * El cliente necesita saberlo para pintar a quién escribe y para entender
         * por qué esta ficha viene sin teléfono, que si no parecería un fallo.
         */
        @Test
        @DisplayName("Se dice si detrás hay un particular, para que el cliente sepa a quién escribe")
        void seDeclaraSiEsUnParticular() {
            conPersonaFisica();

            assertThat(primeraDelListado().getEsPersonaFisica()).isTrue();
            assertThat(ficha().getEsPersonaFisica()).isTrue();
        }

        /**
         * La columna es NOT NULL desde V12, así que esto no puede pasar en
         * producción; se fija el comportamiento para que un nulo no reviente y
         * para que se lea igual que en UsuarioService.anonimizarSiEsPersonaFisica,
         * que hace la misma pregunta sobre el mismo campo.
         */
        @Test
        @DisplayName("Un esPersonaFisica nulo se lee como una entidad, sin reventar")
        void unNuloSeLeeComoEntidad() {
            Protectora sinDeclarar = protectora(EstadoProtectora.ACTIVA, false);
            sinDeclarar.setEsPersonaFisica(null);
            devolver(sinDeclarar);

            assertThat(ficha().getEsPersonaFisica()).isFalse();
            assertThat(ficha().getContacto().getTelefono()).isEqualTo(TELEFONO);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("La nota sale de ValoracionService, y de una sola consulta")
    class Valoraciones {

        /**
         * LA PRUEBA DEL N+1. El listado devuelve una página de protectoras y cada
         * una lleva su nota: pedirla de una en una habría costado dos consultas
         * por fila. Se exige que la variante en lote se llame UNA vez y que la
         * individual no se llame nunca.
         */
        @Test
        @DisplayName("El listado pide las notas de toda la página de una vez")
        void elListadoPideLasNotasEnLote() {
            when(protectoraRepository.buscarParaDirectorio(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(
                            protectora(EstadoProtectora.ACTIVA, false),
                            protectora(EstadoProtectora.ACTIVA, false))));

            protectoraService.directorio(null, Pageable.ofSize(20));

            verify(valoracionService, times(1)).resumenDeVarias(any(Collection.class));
            verify(valoracionService, never()).resumenDe(anyLong());
        }

        @Test
        @DisplayName("La media y el total del listado son los que da ValoracionService")
        void laMediaDelListadoEsLaDelServicio() {
            when(valoracionService.resumenDeVarias(any(Collection.class)))
                    .thenReturn(Map.of(ID_PROTECTORA, new ValoracionService.ResumenValoraciones(
                            new BigDecimal("4.25"), 8L)));

            ProtectoraDirectorioResponse fila = primeraDelListado();

            assertThat(fila.getValoracionMedia()).isEqualByComparingTo("4.25");
            assertThat(fila.getTotalValoraciones()).isEqualTo(8);
        }

        @Test
        @DisplayName("La media y el total de la ficha también, sin recalcularlos")
        void laMediaDeLaFichaEsLaDelServicio() {
            when(valoracionService.resumenDe(ID_PROTECTORA))
                    .thenReturn(new ValoracionService.ResumenValoraciones(
                            new BigDecimal("3.50"), 2L));

            ProtectoraDetalleResponse detalle = ficha();

            assertThat(detalle.getValoracionMedia()).isEqualByComparingTo("3.50");
            assertThat(detalle.getTotalValoraciones()).isEqualTo(2);
        }

        @Test
        @DisplayName("Sin valoraciones no hay media, pero el total es cero y se dice")
        void sinValoracionesLaMediaEsNula() {
            ProtectoraDetalleResponse detalle = ficha();

            assertThat(detalle.getValoracionMedia()).isNull();
            assertThat(detalle.getTotalValoraciones()).isZero();
        }

        @Test
        @DisplayName("La ficha pide las valoraciones acotadas al tope, no todas")
        void laFichaPideLasValoracionesAcotadas() {
            ficha();

            verify(valoracionService).ultimasDe(
                    ID_PROTECTORA, ProtectoraService.VALORACIONES_EN_LA_FICHA);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("Los animales de la ficha son el escaparate, no el histórico")
    class Animales {

        @Test
        @DisplayName("La ficha delega en el catálogo y pide solo los disponibles")
        void laFichaPideSoloLosDisponibles() {
            Pageable paginacion = PageRequest.of(1, 5);

            protectoraService.detalle(ID_PROTECTORA, paginacion);

            // El filtro por DISPONIBLE lo aplica listarDisponiblesDeLaProtectora,
            // que es justamente por lo que se llama a ese método y no al del panel.
            verify(animalAdopcionService).listarDisponiblesDeLaProtectora(ID_PROTECTORA, paginacion);
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("Paginación y filtro de zona")
    class Filtros {

        @Test
        @DisplayName("Un size desmedido se recorta al tope")
        void elTamanyoSeRecorta() {
            protectoraService.directorio(null, PageRequest.of(0, 500));

            assertThat(paginacionPedida().getPageSize())
                    .isEqualTo(ProtectoraService.MAXIMO_POR_PAGINA);
        }

        @Test
        @DisplayName("El listado no impone su propio orden: lo fija la consulta")
        void laPaginacionNoLlevaOrden() {
            protectoraService.directorio(null, PageRequest.of(0, 20));

            // La consulta ordena por lower(nombre) para no depender de la
            // intercalación; un Sort aquí se le añadiría detrás sin aportar nada.
            assertThat(paginacionPedida().getSort().isSorted()).isFalse();
        }

        @Test
        @DisplayName("Una zona concreta viaja como patrón en minúsculas")
        void laZonaViajaComoPatron() {
            protectoraService.directorio("  MaDRid ", Pageable.ofSize(20));

            assertThat(zonaPedida()).isEqualTo("%madrid%");
        }

        /**
         * LA LECCIÓN DE CatalogoAdopcionSpec, y por eso hay prueba. Un parámetro
         * nulo no tiene tipo: PostgreSQL lo toma por bytea y la consulta revienta
         * con "function lower(bytea) does not exist". El servicio tiene que
         * mandar SIEMPRE una cadena.
         */
        @ParameterizedTest(name = "zona = [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("Una zona en blanco es no filtrar, y nunca viaja un nulo")
        void laZonaEnBlancoNoFiltra(String zona) {
            protectoraService.directorio(zona, Pageable.ofSize(20));

            assertThat(zonaPedida()).isEqualTo("%");
        }

        @Test
        @DisplayName("Sin filtro de zona tampoco viaja un nulo")
        void sinZonaTampocoViajaUnNulo() {
            protectoraService.directorio(null, Pageable.ofSize(20));

            assertThat(zonaPedida()).isEqualTo("%");
        }
    }

    // =====================================================================
    // Apoyo
    // =====================================================================

    private ProtectoraDirectorioResponse primeraDelListado() {
        PaginaResponse<ProtectoraDirectorioResponse> pagina =
                protectoraService.directorio(null, Pageable.ofSize(20));
        return pagina.getContent().get(0);
    }

    private ProtectoraDetalleResponse ficha() {
        return protectoraService.detalle(ID_PROTECTORA, Pageable.ofSize(20));
    }

    /** Si la ficha responde con ese estado, en vez de dar 404. */
    private boolean laFichaResponde(EstadoProtectora estado) {
        when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                .thenReturn(Optional.of(protectora(estado, false)));
        try {
            protectoraService.detalle(ID_PROTECTORA, Pageable.ofSize(20));
            return true;
        } catch (ResourceNotFoundException noExiste) {
            return false;
        }
    }

    private String mensajeDelNotFound() {
        try {
            protectoraService.detalle(ID_PROTECTORA, Pageable.ofSize(20));
            throw new AssertionError("Se esperaba un 404");
        } catch (ResourceNotFoundException noExiste) {
            return noExiste.getMessage();
        }
    }

    private void conPersonaFisica() {
        devolver(protectora(EstadoProtectora.ACTIVA, true));
    }

    private void devolver(Protectora protectora) {
        when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                .thenReturn(Optional.of(protectora));
        when(protectoraRepository.buscarParaDirectorio(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(protectora)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<EstadoProtectora> estadosPedidos() {
        ArgumentCaptor<Collection> captor = ArgumentCaptor.forClass(Collection.class);
        verify(protectoraRepository).buscarParaDirectorio(captor.capture(), any(), any());
        return List.copyOf((Collection<EstadoProtectora>) captor.getValue());
    }

    private String zonaPedida() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(protectoraRepository).buscarParaDirectorio(any(), captor.capture(), any());
        return captor.getValue();
    }

    private Pageable paginacionPedida() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(protectoraRepository).buscarParaDirectorio(any(), any(), captor.capture());
        return captor.getValue();
    }

    private static Protectora protectora(EstadoProtectora estado, boolean esPersonaFisica) {
        Usuario cuenta = new Usuario();
        cuenta.setId(ID_CUENTA);
        cuenta.setNombre("Huellas de Getafe");
        cuenta.setEmail(EMAIL);
        cuenta.setTelefono(TELEFONO);
        cuenta.setRol(Rol.PROTECTORA);
        cuenta.setActivo(true);

        Protectora protectora = new Protectora();
        protectora.setId(ID_PROTECTORA);
        protectora.setUsuario(cuenta);
        protectora.setNombreOrganizacion("Huellas de Getafe");
        protectora.setDireccion(DIRECCION);
        protectora.setZonaGeografica("Madrid");
        protectora.setEstado(estado);
        protectora.setEsPersonaFisica(esPersonaFisica);
        return protectora;
    }
}
