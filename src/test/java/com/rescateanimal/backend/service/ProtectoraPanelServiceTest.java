package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ResumenPanelResponse;
import com.rescateanimal.backend.dto.response.SolicitudAdopcionResponse;
import com.rescateanimal.backend.dto.response.ValoracionesPanelResponse;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.Valoracion;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.ValoracionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas del panel de la protectora (RF-10): quién entra, qué ve y qué no.
 *
 * Las consultas se simulan aquí; que su JPQL sea válido y filtre lo que dice lo
 * comprueba PanelProtectoraQueryTest contra PostgreSQL de verdad.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProtectoraPanelServiceTest {

    private static final Long ID_CUENTA = 7L;
    private static final Long ID_PROTECTORA = 40L;

    /** Otra protectora, con otra cuenta: nada suyo debe aparecer nunca. */
    private static final Long ID_CUENTA_AJENA = 8L;
    private static final Long ID_PROTECTORA_AJENA = 41L;

    private static final Long ID_ANIMAL = 100L;

    @Mock
    private ProtectoraRepository protectoraRepository;
    @Mock
    private AnimalAdopcionRepository animalAdopcionRepository;
    @Mock
    private MatchRegistroRepository matchRegistroRepository;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ValoracionRepository valoracionRepository;
    @Mock
    private AnimalAdopcionService animalAdopcionService;

    private ProtectoraPanelService panel;

    @BeforeEach
    void prepararServicio() {
        panel = new ProtectoraPanelService(protectoraRepository, animalAdopcionRepository,
                matchRegistroRepository, chatRepository, valoracionRepository,
                animalAdopcionService);

        when(protectoraRepository.findByUsuarioId(ID_CUENTA))
                .thenReturn(Optional.of(protectora(ID_PROTECTORA, ID_CUENTA, EstadoProtectora.ACTIVA)));
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Quién puede abrir el panel")
    class Acceso {

        @Test
        @DisplayName("Una protectora PENDIENTE no accede a ningún endpoint del panel")
        void laPendienteNoAccede() {
            estadoDeLaProtectora(EstadoProtectora.PENDIENTE);

            // Los cuatro, no solo uno: el rol PROTECTORA lo tiene igual una
            // protectora sin verificar, así que cada endpoint debe cerrarse.
            for (Runnable endpoint : todosLosEndpoints()) {
                assertThatThrownBy(endpoint::run)
                        .isInstanceOf(CuentaNoAutorizadaException.class)
                        .satisfies(e -> {
                            CuentaNoAutorizadaException ex = (CuentaNoAutorizadaException) e;
                            assertThat(ex.getCodigo()).isEqualTo("PROTECTORA_PENDIENTE");
                            assertThat(ex.getMessage()).contains("pendiente de verificación");
                        });
            }
        }

        @Test
        @DisplayName("Una protectora RECHAZADA tampoco accede, y se le dice por qué")
        void laRechazadaNoAccede() {
            estadoDeLaProtectora(EstadoProtectora.RECHAZADA);

            for (Runnable endpoint : todosLosEndpoints()) {
                assertThatThrownBy(endpoint::run)
                        .isInstanceOf(CuentaNoAutorizadaException.class)
                        .satisfies(e -> assertThat(((CuentaNoAutorizadaException) e).getCodigo())
                                .isEqualTo("PROTECTORA_RECHAZADA"));
            }
        }

        @Test
        @DisplayName("Rechazada la entrada, no se consulta ningún dato del panel")
        void elRechazoNoConsultaNada() {
            estadoDeLaProtectora(EstadoProtectora.PENDIENTE);

            assertThatThrownBy(() -> panel.resumen(ID_CUENTA))
                    .isInstanceOf(CuentaNoAutorizadaException.class);

            // Importa que la comprobación vaya ANTES de las consultas: si no,
            // una protectora sin verificar provocaría igualmente el trabajo.
            verifyNoInteractions(animalAdopcionRepository, matchRegistroRepository,
                    valoracionRepository, animalAdopcionService);
        }

        @Test
        @DisplayName("Una cuenta con rol PROTECTORA pero sin protectora asociada da 403, no 500")
        void sinProtectoraAsociadaDa403() {
            when(protectoraRepository.findByUsuarioId(ID_CUENTA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> panel.resumen(ID_CUENTA))
                    .isInstanceOf(CuentaNoAutorizadaException.class)
                    .satisfies(e -> assertThat(((CuentaNoAutorizadaException) e).getCodigo())
                            .isEqualTo("SIN_PROTECTORA"));
        }

        @Test
        @DisplayName("Una protectora ACTIVA entra sin problemas")
        void laActivaEntra() {
            when(matchRegistroRepository.buscarSolicitudesDeProtectora(anyLong(), any()))
                    .thenReturn(List.of());

            assertThat(panel.solicitudes(ID_CUENTA)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Aislamiento entre protectoras")
    class Aislamiento {

        @Test
        @DisplayName("El panel consulta SIEMPRE por la protectora del token, nunca por otra")
        void soloConsultaLaPropia() {
            when(animalAdopcionService.listarDeLaProtectora(anyLong(), any()))
                    .thenReturn(paginaVacia());
            when(matchRegistroRepository.buscarSolicitudesDeProtectora(anyLong(), any()))
                    .thenReturn(List.of());
            when(valoracionRepository.buscarDeProtectoraConAutor(anyLong())).thenReturn(List.of());
            when(animalAdopcionRepository.contarPorEstado(anyLong())).thenReturn(List.of());

            Pageable pagina = PageRequest.of(0, 20);
            panel.animales(ID_CUENTA, pagina);
            panel.solicitudes(ID_CUENTA);
            panel.valoraciones(ID_CUENTA);
            panel.resumen(ID_CUENTA);

            // Con el id de SU protectora, resuelto desde el token. Ningún
            // endpoint recibe un id del cliente, así que pedir el panel de otra
            // no es algo que se rechace: es algo que no se puede ni expresar.
            verify(animalAdopcionService).listarDeLaProtectora(eq(ID_PROTECTORA), any());
            verify(matchRegistroRepository)
                    .buscarSolicitudesDeProtectora(ID_PROTECTORA, TipoPublicacion.ADOPCION);
            verify(valoracionRepository).buscarDeProtectoraConAutor(ID_PROTECTORA);
            verify(animalAdopcionRepository).contarPorEstado(ID_PROTECTORA);
        }

        @Test
        @DisplayName("Dos cuentas distintas resuelven paneles distintos")
        void cadaCuentaResuelveElSuyo() {
            when(protectoraRepository.findByUsuarioId(ID_CUENTA_AJENA)).thenReturn(
                    Optional.of(protectora(ID_PROTECTORA_AJENA, ID_CUENTA_AJENA,
                            EstadoProtectora.ACTIVA)));
            when(animalAdopcionRepository.contarPorEstado(anyLong())).thenReturn(List.of());

            panel.resumen(ID_CUENTA);
            panel.resumen(ID_CUENTA_AJENA);

            verify(animalAdopcionRepository).contarPorEstado(ID_PROTECTORA);
            verify(animalAdopcionRepository).contarPorEstado(ID_PROTECTORA_AJENA);
        }

        @Test
        @DisplayName("El listado de animales usa el id de la protectora, no el de su cuenta")
        void usaElIdDeLaProtectoraNoElDeLaCuenta() {
            when(animalAdopcionService.listarDeLaProtectora(anyLong(), any()))
                    .thenReturn(paginaVacia());

            panel.animales(ID_CUENTA, PageRequest.of(0, 20));

            // Confundir los dos ids es el error que enseñaría el panel de otra
            // protectora en cuanto los identificadores no coincidieran.
            verify(animalAdopcionService).listarDeLaProtectora(eq(ID_PROTECTORA), any());
        }
    }

    @Nested
    @DisplayName("Solicitudes recibidas (RF-10)")
    class Solicitudes {

        @Test
        @DisplayName("Una solicitud trae al interesado, su animal y el chat abierto")
        void laSolicitudTraeLoNecesario() {
            sembrarUnaSolicitud(500L, 900L);

            List<SolicitudAdopcionResponse> solicitudes = panel.solicitudes(ID_CUENTA);

            assertThat(solicitudes).hasSize(1);
            SolicitudAdopcionResponse solicitud = solicitudes.get(0);
            assertThat(solicitud.getId()).isEqualTo(500L);
            assertThat(solicitud.getInteresado().getNombre()).isEqualTo("Ana Ciudadana");
            assertThat(solicitud.getAnimalId()).isEqualTo(ID_ANIMAL);
            assertThat(solicitud.getAnimalNombre()).isEqualTo("Luna");
            assertThat(solicitud.getAnimalEstado()).isEqualTo("DISPONIBLE");
            assertThat(solicitud.getChatId()).isEqualTo(900L);
        }

        @Test
        @DisplayName("El interesado viaja sin email ni teléfono, aunque la cuenta los tenga")
        void elInteresadoNoExponeContacto() {
            sembrarUnaSolicitud(500L, 900L);

            SolicitudAdopcionResponse solicitud = panel.solicitudes(ID_CUENTA).get(0);

            // AutorResponse no tiene dónde ponerlos, y es deliberado: no hay
            // ningún campo con el que el ciudadano declare que quiere
            // compartirlos, así que RNF-10 no permite suponer que sí.
            assertThat(solicitud.getInteresado().getId()).isEqualTo(77L);
            assertThat(solicitud.getInteresado().getNombre()).isEqualTo("Ana Ciudadana");
            assertThat(solicitud.getInteresado().getFotoUrl()).isEqualTo("https://cdn.test/ana.jpg");
        }

        @Test
        @DisplayName("Solo se piden matches con interesado = true: los descartes no son solicitudes")
        void soloPideLosInteresados() {
            when(matchRegistroRepository.buscarSolicitudesDeProtectora(anyLong(), any()))
                    .thenReturn(List.of());

            panel.solicitudes(ID_CUENTA);

            // El filtro vive en la consulta, así que lo que se comprueba aquí es
            // que se llama a la que filtra y con el tipo ADOPCION. Que filtre de
            // verdad lo verifica PanelProtectoraQueryTest contra PostgreSQL.
            verify(matchRegistroRepository)
                    .buscarSolicitudesDeProtectora(ID_PROTECTORA, TipoPublicacion.ADOPCION);
        }

        @Test
        @DisplayName("Sin solicitudes no se consulta ni animales ni chats")
        void sinSolicitudesNoConsultaMas() {
            when(matchRegistroRepository.buscarSolicitudesDeProtectora(anyLong(), any()))
                    .thenReturn(List.of());

            assertThat(panel.solicitudes(ID_CUENTA)).isEmpty();
            verifyNoInteractions(chatRepository);
        }

        @Test
        @DisplayName("Un animal que ya no está no tumba el listado entero")
        void elAnimalAusenteNoRompe() {
            MatchRegistro solicitud = match(500L, ID_ANIMAL, ciudadano());
            when(matchRegistroRepository.buscarSolicitudesDeProtectora(anyLong(), any()))
                    .thenReturn(List.of(solicitud));
            when(animalAdopcionRepository.buscarConProtectoraPorIds(any())).thenReturn(List.of());
            when(chatRepository.findByMatchIdIn(any())).thenReturn(List.of());

            SolicitudAdopcionResponse resultado = panel.solicitudes(ID_CUENTA).get(0);

            assertThat(resultado.getAnimalNombre()).isNull();
            assertThat(resultado.getAnimalEstado()).isNull();
            assertThat(resultado.getId()).isEqualTo(500L);
        }
    }

    @Nested
    @DisplayName("Resumen (RF-10)")
    class Resumen {

        @Test
        @DisplayName("Agrega los contadores de cada estado y los suma")
        void agregaLosContadores() {
            when(animalAdopcionRepository.contarPorEstado(ID_PROTECTORA)).thenReturn(List.of(
                    new Conteo(EstadoAnimal.DISPONIBLE, 4),
                    new Conteo(EstadoAnimal.ADOPTADO, 3),
                    new Conteo(EstadoAnimal.RETIRADO, 2)));
            when(matchRegistroRepository.contarSolicitudesPendientes(
                    ID_PROTECTORA, TipoPublicacion.ADOPCION, EstadoAnimal.DISPONIBLE))
                    .thenReturn(5L);
            when(matchRegistroRepository.contarSolicitudesDeProtectora(
                    ID_PROTECTORA, TipoPublicacion.ADOPCION)).thenReturn(11L);
            when(valoracionRepository.mediaDeProtectora(ID_PROTECTORA)).thenReturn(4.5);
            when(valoracionRepository.countByProtectoraId(ID_PROTECTORA)).thenReturn(6L);

            ResumenPanelResponse resumen = panel.resumen(ID_CUENTA);

            assertThat(resumen.getAnimalesDisponibles()).isEqualTo(4);
            assertThat(resumen.getAnimalesAdoptados()).isEqualTo(3);
            assertThat(resumen.getAnimalesRetirados()).isEqualTo(2);
            assertThat(resumen.getAnimalesTotales()).isEqualTo(9);
            assertThat(resumen.getSolicitudesPendientes()).isEqualTo(5);
            assertThat(resumen.getSolicitudesTotales()).isEqualTo(11);
            assertThat(resumen.getValoracionMedia()).isEqualByComparingTo("4.50");
            assertThat(resumen.getTotalValoraciones()).isEqualTo(6);
        }

        @Test
        @DisplayName("Un estado sin animales cuenta cero, no desaparece del resumen")
        void elEstadoAusenteCuentaCero() {
            // La consulta agrupada solo devuelve fila para los estados que
            // existan: sin esto, una protectora sin adopciones no tendría
            // animalesAdoptados en la respuesta.
            when(animalAdopcionRepository.contarPorEstado(ID_PROTECTORA))
                    .thenReturn(List.of(new Conteo(EstadoAnimal.DISPONIBLE, 2)));

            ResumenPanelResponse resumen = panel.resumen(ID_CUENTA);

            assertThat(resumen.getAnimalesDisponibles()).isEqualTo(2);
            assertThat(resumen.getAnimalesAdoptados()).isZero();
            assertThat(resumen.getAnimalesRetirados()).isZero();
            assertThat(resumen.getAnimalesTotales()).isEqualTo(2);
        }

        @Test
        @DisplayName("Una protectora recién verificada tiene todo a cero, no nulos")
        void laProtectoraNuevaTieneTodoACero() {
            when(animalAdopcionRepository.contarPorEstado(ID_PROTECTORA)).thenReturn(List.of());

            ResumenPanelResponse resumen = panel.resumen(ID_CUENTA);

            assertThat(resumen.getAnimalesTotales()).isZero();
            assertThat(resumen.getSolicitudesTotales()).isZero();
            assertThat(resumen.getTotalValoraciones()).isZero();
        }

        @Test
        @DisplayName("Sin valoraciones la media es nula, nunca cero")
        void sinValoracionesLaMediaEsNula() {
            when(animalAdopcionRepository.contarPorEstado(ID_PROTECTORA)).thenReturn(List.of());
            when(valoracionRepository.mediaDeProtectora(ID_PROTECTORA)).thenReturn(null);

            // Un cero se pintaría como la peor nota posible; lo que ocurre es
            // que todavía no hay ninguna.
            assertThat(panel.resumen(ID_CUENTA).getValoracionMedia()).isNull();
        }

        @Test
        @DisplayName("La media se redondea a los dos decimales que expone la API")
        void laMediaSeRedondeaADosDecimales() {
            when(animalAdopcionRepository.contarPorEstado(ID_PROTECTORA)).thenReturn(List.of());
            when(valoracionRepository.mediaDeProtectora(ID_PROTECTORA))
                    .thenReturn(4.333333333333333);

            assertThat(panel.resumen(ID_CUENTA).getValoracionMedia())
                    .isEqualByComparingTo(new BigDecimal("4.33"));
        }
    }

    @Nested
    @DisplayName("Valoraciones (RF-10, RF-23)")
    class Valoraciones {

        @Test
        @DisplayName("Sin el bloque 9 devuelve estructura vacía, no un error")
        void sinBloque9DevuelveVacio() {
            when(valoracionRepository.buscarDeProtectoraConAutor(ID_PROTECTORA))
                    .thenReturn(List.of());

            ValoracionesPanelResponse respuesta = panel.valoraciones(ID_CUENTA);

            assertThat(respuesta.getValoraciones()).isEmpty();
            assertThat(respuesta.getMedia()).isNull();
            assertThat(respuesta.getTotal()).isZero();
        }

        @Test
        @DisplayName("Con valoraciones calcula la media sobre las que devuelve")
        void calculaLaMediaSobreLasDevueltas() {
            when(valoracionRepository.buscarDeProtectoraConAutor(ID_PROTECTORA))
                    .thenReturn(List.of(valoracion(1L, 5), valoracion(2L, 4), valoracion(3L, 3)));

            ValoracionesPanelResponse respuesta = panel.valoraciones(ID_CUENTA);

            assertThat(respuesta.getTotal()).isEqualTo(3);
            assertThat(respuesta.getMedia()).isEqualByComparingTo("4.00");
            assertThat(respuesta.getValoraciones()).extracting("puntuacion")
                    .containsExactly(5, 4, 3);
        }

        @Test
        @DisplayName("La media sale de las valoraciones, no de PROTECTORA.valoracionMedia")
        void laMediaNoSaleDeLaColumna() {
            // La columna está documentada como derivada y sin mantener: si el
            // service la leyera, aquí saldría 1.00 en vez de 5.00.
            Protectora conMediaFalsa = protectora(ID_PROTECTORA, ID_CUENTA, EstadoProtectora.ACTIVA);
            conMediaFalsa.setValoracionMedia(new BigDecimal("1.00"));
            when(protectoraRepository.findByUsuarioId(ID_CUENTA))
                    .thenReturn(Optional.of(conMediaFalsa));
            when(valoracionRepository.buscarDeProtectoraConAutor(ID_PROTECTORA))
                    .thenReturn(List.of(valoracion(1L, 5)));

            assertThat(panel.valoraciones(ID_CUENTA).getMedia()).isEqualByComparingTo("5.00");
        }
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /** Los cuatro endpoints del panel, para comprobar reglas que valen en todos. */
    private List<Runnable> todosLosEndpoints() {
        return List.of(
                () -> panel.animales(ID_CUENTA, PageRequest.of(0, 20)),
                () -> panel.solicitudes(ID_CUENTA),
                () -> panel.valoraciones(ID_CUENTA),
                () -> panel.resumen(ID_CUENTA));
    }

    private void estadoDeLaProtectora(EstadoProtectora estado) {
        when(protectoraRepository.findByUsuarioId(ID_CUENTA))
                .thenReturn(Optional.of(protectora(ID_PROTECTORA, ID_CUENTA, estado)));
    }

    private void sembrarUnaSolicitud(Long matchId, Long chatId) {
        MatchRegistro solicitud = match(matchId, ID_ANIMAL, ciudadano());

        when(matchRegistroRepository.buscarSolicitudesDeProtectora(anyLong(), any()))
                .thenReturn(List.of(solicitud));
        when(animalAdopcionRepository.buscarConProtectoraPorIds(any()))
                .thenReturn(List.of(animal(ID_ANIMAL, "Luna", EstadoAnimal.DISPONIBLE)));

        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setMatch(solicitud);
        when(chatRepository.findByMatchIdIn(any())).thenReturn(List.of(chat));
    }

    private static Protectora protectora(Long id, Long cuentaId, EstadoProtectora estado) {
        Usuario cuenta = new Usuario();
        cuenta.setId(cuentaId);
        cuenta.setRol(Rol.PROTECTORA);
        cuenta.setActivo(true);

        Protectora protectora = new Protectora();
        protectora.setId(id);
        protectora.setUsuario(cuenta);
        protectora.setNombreOrganizacion("Protectora " + id);
        protectora.setEstado(estado);
        return protectora;
    }

    private static Usuario ciudadano() {
        Usuario usuario = new Usuario();
        usuario.setId(77L);
        usuario.setNombre("Ana Ciudadana");
        usuario.setEmail("ana@test.es");
        usuario.setTelefono("600123456");
        usuario.setFotoUrl("https://cdn.test/ana.jpg");
        usuario.setRol(Rol.CIUDADANO);
        return usuario;
    }

    private static MatchRegistro match(Long id, Long animalId, Usuario autor) {
        MatchRegistro registro = new MatchRegistro();
        registro.setId(id);
        registro.setUsuario(autor);
        registro.setPublicacionId(animalId);
        registro.setTipoPublicacion(TipoPublicacion.ADOPCION);
        registro.setInteresado(true);
        registro.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        return registro;
    }

    private static AnimalAdopcion animal(Long id, String nombre, EstadoAnimal estado) {
        AnimalAdopcion animal = new AnimalAdopcion();
        animal.setId(id);
        animal.setNombre(nombre);
        animal.setEstado(estado);
        return animal;
    }

    private static Valoracion valoracion(Long id, int puntuacion) {
        Valoracion valoracion = new Valoracion();
        valoracion.setId(id);
        valoracion.setUsuario(ciudadano());
        valoracion.setPuntuacion(puntuacion);
        valoracion.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        return valoracion;
    }

    private static PaginaResponse<com.rescateanimal.backend.dto.response.AnimalAdopcionResponse>
    paginaVacia() {
        return PaginaResponse
                .<com.rescateanimal.backend.dto.response.AnimalAdopcionResponse>builder()
                .content(List.of()).page(0).size(20).totalElements(0).totalPages(0).last(true)
                .build();
    }

    /** La proyección del recuento agrupado, para poder simularla. */
    private record Conteo(EstadoAnimal estado, long total)
            implements AnimalAdopcionRepository.ConteoPorEstado {

        @Override
        public EstadoAnimal getEstado() {
            return estado;
        }

        @Override
        public long getTotal() {
            return total;
        }
    }
}
