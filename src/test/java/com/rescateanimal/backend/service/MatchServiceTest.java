package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearMatchRequest;
import com.rescateanimal.backend.dto.response.LimiteMatchResponse;
import com.rescateanimal.backend.dto.response.MatchResponse;
import com.rescateanimal.backend.exception.LimiteMatchesAlcanzadoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.PublicacionPropiaException;
import com.rescateanimal.backend.exception.PublicacionYaDeslizadaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.LimiteMatchDiario;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.LimiteMatchDiarioRepository;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas del deslizamiento: quién puede deslizar qué, cuándo se abre chat y
 * cuándo se consume cuota (RF-08, RF-15, RF-24).
 *
 * Las consultas nativas del contador (el INSERT ... ON CONFLICT y el UPDATE
 * atómico) se simulan aquí; que su SQL sea válido lo comprueba
 * MatchFlujoCompletoTest contra PostgreSQL de verdad.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatchServiceTest {

    private static final int LIMITE_DIARIO = 20;
    private static final Long ID_USUARIO = 7L;
    private static final Long ID_AUTOR_AVISTAMIENTO = 8L;
    private static final Long ID_DUENYO_ALERTA = 9L;
    private static final Long ID_AVISTAMIENTO = 33L;

    @Mock
    private MatchRegistroRepository matchRegistroRepository;
    @Mock
    private LimiteMatchDiarioRepository limiteMatchDiarioRepository;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private AvistamientoRepository avistamientoRepository;
    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private NotificacionService notificacionService;

    private MatchService matchService;

    @BeforeEach
    void prepararServicio() {
        matchService = new MatchService(matchRegistroRepository, limiteMatchDiarioRepository,
                chatRepository, avistamientoRepository, usuarioRepository, notificacionService,
                LIMITE_DIARIO);

        when(avistamientoRepository.findById(ID_AVISTAMIENTO)).thenReturn(Optional.of(avistamiento()));
        when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario(ID_USUARIO)));
        when(matchRegistroRepository.save(any(MatchRegistro.class))).thenAnswer(i -> {
            MatchRegistro m = i.getArgument(0);
            m.setId(100L);
            return m;
        });
        when(chatRepository.save(any(Chat.class))).thenAnswer(i -> {
            Chat c = i.getArgument(0);
            c.setId(200L);
            return c;
        });
        // Por defecto queda cupo.
        when(limiteMatchDiarioRepository.incrementarSiHayCupo(anyLong(), any(), anyInt())).thenReturn(1);
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Match (RF-08)")
    class Match {

        @Test
        @DisplayName("Un match crea el registro, abre el chat y avisa a quien registró el avistamiento")
        void elMatchAbreChatYNotifica() {
            MatchResponse respuesta = matchService.deslizar(peticion(true), ID_USUARIO);

            assertThat(respuesta.getId()).isEqualTo(100L);
            assertThat(respuesta.getChatId()).isEqualTo(200L);
            assertThat(respuesta.getInteresado()).isTrue();

            ArgumentCaptor<Chat> chat = ArgumentCaptor.forClass(Chat.class);
            verify(chatRepository).save(chat.capture());
            assertThat(chat.getValue().getMatch().getId()).isEqualTo(100L);

            // El destinatario es el autor del avistamiento, no el dueño de la
            // alerta: son los dos extremos del chat que abre el match, y el
            // dueño de la alerta es normalmente quien acaba de deslizar.
            verify(notificacionService).notificarNuevoMatch(
                    argThatTieneId(ID_AUTOR_AVISTAMIENTO), org.mockito.ArgumentMatchers.eq(100L),
                    org.mockito.ArgumentMatchers.eq(ID_USUARIO));
        }

        @Test
        @DisplayName("El registro guardado queda marcado como interesado")
        void elRegistroQuedaMarcadoComoInteresado() {
            matchService.deslizar(peticion(true), ID_USUARIO);

            ArgumentCaptor<MatchRegistro> registro = ArgumentCaptor.forClass(MatchRegistro.class);
            verify(matchRegistroRepository).save(registro.capture());

            assertThat(registro.getValue().getInteresado()).isTrue();
            assertThat(registro.getValue().getPublicacionId()).isEqualTo(ID_AVISTAMIENTO);
            assertThat(registro.getValue().getTipoPublicacion()).isEqualTo(TipoPublicacion.AVISTAMIENTO);
        }

        @Test
        @DisplayName("Un match consume cuota diaria")
        void elMatchConsumeCuota() {
            matchService.deslizar(peticion(true), ID_USUARIO);

            verify(limiteMatchDiarioRepository).crearContadorSiNoExiste(ID_USUARIO, LocalDate.now());
            verify(limiteMatchDiarioRepository)
                    .incrementarSiHayCupo(ID_USUARIO, LocalDate.now(), LIMITE_DIARIO);
        }
    }

    @Nested
    @DisplayName("Descarte (RF-15)")
    class Descarte {

        @Test
        @DisplayName("Un descarte no abre chat ni notifica a nadie")
        void elDescarteNoAbreChatNiNotifica() {
            MatchResponse respuesta = matchService.deslizar(peticion(false), ID_USUARIO);

            assertThat(respuesta.getInteresado()).isFalse();
            assertThat(respuesta.getChatId()).isNull();
            verifyNoInteractions(chatRepository);
            verifyNoInteractions(notificacionService);
        }

        @Test
        @DisplayName("Un descarte no consume cuota diaria")
        void elDescarteNoConsumeCuota() {
            matchService.deslizar(peticion(false), ID_USUARIO);

            verifyNoInteractions(limiteMatchDiarioRepository);
        }

        @Test
        @DisplayName("El descarte sí queda registrado: es lo que impide que reaparezca en el feed")
        void elDescarteQuedaRegistrado() {
            matchService.deslizar(peticion(false), ID_USUARIO);

            ArgumentCaptor<MatchRegistro> registro = ArgumentCaptor.forClass(MatchRegistro.class);
            verify(matchRegistroRepository).save(registro.capture());
            assertThat(registro.getValue().getInteresado()).isFalse();
        }
    }

    @Nested
    @DisplayName("Límite diario (RF-24)")
    class Limite {

        @Test
        @DisplayName("Sin cupo se rechaza con el límite y la hora de reinicio")
        void sinCupoSeRechaza() {
            when(limiteMatchDiarioRepository.incrementarSiHayCupo(anyLong(), any(), anyInt()))
                    .thenReturn(0);

            assertThatThrownBy(() -> matchService.deslizar(peticion(true), ID_USUARIO))
                    .isInstanceOf(LimiteMatchesAlcanzadoException.class)
                    .satisfies(e -> {
                        LimiteMatchesAlcanzadoException ex = (LimiteMatchesAlcanzadoException) e;
                        assertThat(ex.getLimiteDiario()).isEqualTo(LIMITE_DIARIO);
                        assertThat(ex.getReiniciaEn())
                                .isEqualTo(LocalDate.now().plusDays(1).atStartOfDay());
                        assertThat(ex.getMessage()).contains("mañana");
                    });
        }

        @Test
        @DisplayName("Al agotarse el cupo no se guarda nada: ni registro, ni chat, ni notificación")
        void sinCupoNoSeGuardaNada() {
            when(limiteMatchDiarioRepository.incrementarSiHayCupo(anyLong(), any(), anyInt()))
                    .thenReturn(0);

            assertThatThrownBy(() -> matchService.deslizar(peticion(true), ID_USUARIO))
                    .isInstanceOf(LimiteMatchesAlcanzadoException.class);

            verify(matchRegistroRepository, never()).save(any());
            verifyNoInteractions(chatRepository);
            verifyNoInteractions(notificacionService);
        }

        @Test
        @DisplayName("Agotado el cupo, un descarte sigue funcionando")
        void conElCupoAgotadoElDescarteSigueFuncionando() {
            when(limiteMatchDiarioRepository.incrementarSiHayCupo(anyLong(), any(), anyInt()))
                    .thenReturn(0);

            MatchResponse respuesta = matchService.deslizar(peticion(false), ID_USUARIO);

            assertThat(respuesta.getInteresado()).isFalse();
            verify(matchRegistroRepository).save(any());
        }

        @Test
        @DisplayName("Sin fila del día, el consumo se cuenta como cero")
        void sinFilaDelDiaElConsumoEsCero() {
            when(limiteMatchDiarioRepository.findByUsuarioIdAndFecha(ID_USUARIO, LocalDate.now()))
                    .thenReturn(Optional.empty());

            LimiteMatchResponse limite = matchService.consultarLimite(ID_USUARIO);

            assertThat(limite.getConsumidos()).isZero();
            assertThat(limite.getRestantes()).isEqualTo(LIMITE_DIARIO);
            assertThat(limite.getLimiteDiario()).isEqualTo(LIMITE_DIARIO);
            assertThat(limite.getReiniciaEn()).isEqualTo(LocalDate.now().plusDays(1).atStartOfDay());
        }

        @Test
        @DisplayName("Con el cupo agotado, restantes es cero y nunca negativo")
        void restantesNuncaEsNegativo() {
            LimiteMatchDiario fila = new LimiteMatchDiario();
            fila.setContador(LIMITE_DIARIO + 5);
            when(limiteMatchDiarioRepository.findByUsuarioIdAndFecha(ID_USUARIO, LocalDate.now()))
                    .thenReturn(Optional.of(fila));

            assertThat(matchService.consultarLimite(ID_USUARIO).getRestantes()).isZero();
        }
    }

    @Nested
    @DisplayName("Rechazos")
    class Rechazos {

        @Test
        @DisplayName("Deslizar dos veces la misma publicación se rechaza")
        void dobleSwipeSeRechaza() {
            when(matchRegistroRepository.existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                    ID_USUARIO, ID_AVISTAMIENTO, TipoPublicacion.AVISTAMIENTO)).thenReturn(true);

            assertThatThrownBy(() -> matchService.deslizar(peticion(true), ID_USUARIO))
                    .isInstanceOf(PublicacionYaDeslizadaException.class);

            verify(matchRegistroRepository, never()).save(any());
        }

        @Test
        @DisplayName("El doble swipe no gasta cuota: se comprueba antes de consumirla")
        void elDobleSwipeNoGastaCuota() {
            when(matchRegistroRepository.existsByUsuarioIdAndPublicacionIdAndTipoPublicacion(
                    anyLong(), anyLong(), any())).thenReturn(true);

            assertThatThrownBy(() -> matchService.deslizar(peticion(true), ID_USUARIO))
                    .isInstanceOf(PublicacionYaDeslizadaException.class);

            verifyNoInteractions(limiteMatchDiarioRepository);
        }

        @Test
        @DisplayName("Un avistamiento inexistente se rechaza con 404")
        void avistamientoInexistenteSeRechaza() {
            when(avistamientoRepository.findById(ID_AVISTAMIENTO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> matchService.deslizar(peticion(true), ID_USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Nadie desliza su propia publicación: abriría un chat consigo mismo")
        void noSePuedeDeslizarLoPropio() {
            assertThatThrownBy(() -> matchService.deslizar(peticion(true), ID_AUTOR_AVISTAMIENTO))
                    .isInstanceOf(PublicacionPropiaException.class);

            verify(matchRegistroRepository, never()).save(any());
        }

        @Test
        @DisplayName("Sobre una alerta ya cerrada no se desliza: el animal apareció")
        void noSeDeslizaSobreUnaAlertaCerrada() {
            when(avistamientoRepository.findById(ID_AVISTAMIENTO))
                    .thenReturn(Optional.of(avistamiento(EstadoAlerta.ENCONTRADA)));

            assertThatThrownBy(() -> matchService.deslizar(peticion(true), ID_USUARIO))
                    .isInstanceOf(OperacionNoPermitidaException.class)
                    .satisfies(e -> assertThat(((OperacionNoPermitidaException) e).getCodigo())
                            .isEqualTo("ALERTA_NO_ACTIVA"));

            // Ni registro, ni chat, ni aviso a nadie por un animal ya encontrado.
            verify(matchRegistroRepository, never()).save(any());
            verifyNoInteractions(chatRepository);
            verifyNoInteractions(notificacionService);
        }

        @Test
        @DisplayName("La alerta cerrada se comprueba antes de gastar cuota")
        void laAlertaCerradaNoGastaCuota() {
            when(avistamientoRepository.findById(ID_AVISTAMIENTO))
                    .thenReturn(Optional.of(avistamiento(EstadoAlerta.ENCONTRADA)));

            assertThatThrownBy(() -> matchService.deslizar(peticion(true), ID_USUARIO))
                    .isInstanceOf(OperacionNoPermitidaException.class);

            verifyNoInteractions(limiteMatchDiarioRepository);
        }

        @Test
        @DisplayName("El descarte sobre una alerta cerrada también se rechaza")
        void elDescarteSobreAlertaCerradaTambienSeRechaza() {
            // Se bloquean los dos sentidos, igual que con la publicación propia:
            // la tarjeta está muerta y el 400 le dice al cliente que refresque el
            // feed, en vez de aceptar en silencio un descarte que ya no aporta nada.
            when(avistamientoRepository.findById(ID_AVISTAMIENTO))
                    .thenReturn(Optional.of(avistamiento(EstadoAlerta.ENCONTRADA)));

            assertThatThrownBy(() -> matchService.deslizar(peticion(false), ID_USUARIO))
                    .isInstanceOf(OperacionNoPermitidaException.class);

            verify(matchRegistroRepository, never()).save(any());
        }

        @Test
        @DisplayName("ADOPCION todavía no se admite y se dice explícitamente")
        void elTipoAdopcionTodaviaNoSeAdmite() {
            CrearMatchRequest peticion = new CrearMatchRequest(
                    ID_AVISTAMIENTO, TipoPublicacion.ADOPCION, true);

            assertThatThrownBy(() -> matchService.deslizar(peticion, ID_USUARIO))
                    .isInstanceOf(OperacionNoPermitidaException.class)
                    .satisfies(e -> assertThat(((OperacionNoPermitidaException) e).getCodigo())
                            .isEqualTo("TIPO_PUBLICACION_NO_DISPONIBLE"));

            verifyNoInteractions(avistamientoRepository);
        }
    }

    @Nested
    @DisplayName("Listado de matches")
    class Listado {

        @Test
        @DisplayName("Cada match se devuelve con el id de su chat, en una sola consulta")
        void cadaMatchLlevaSuChat() {
            MatchRegistro uno = registro(1L);
            MatchRegistro dos = registro(2L);
            when(matchRegistroRepository.findByUsuarioIdAndInteresadoTrueOrderByCreatedAtDesc(ID_USUARIO))
                    .thenReturn(List.of(uno, dos));
            when(chatRepository.findByMatchIdIn(List.of(1L, 2L)))
                    .thenReturn(List.of(chat(11L, uno), chat(22L, dos)));

            List<MatchResponse> matches = matchService.listarMisMatches(ID_USUARIO);

            assertThat(matches).extracting(MatchResponse::getId, MatchResponse::getChatId)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1L, 11L),
                            org.assertj.core.groups.Tuple.tuple(2L, 22L));
            verify(chatRepository).findByMatchIdIn(List.of(1L, 2L));
        }

        @Test
        @DisplayName("Sin matches no se consultan los chats")
        void sinMatchesNoSeConsultanChats() {
            when(matchRegistroRepository.findByUsuarioIdAndInteresadoTrueOrderByCreatedAtDesc(ID_USUARIO))
                    .thenReturn(List.of());

            assertThat(matchService.listarMisMatches(ID_USUARIO)).isEmpty();
            verify(chatRepository, never()).findByMatchIdIn(any());
        }
    }

    // ---------------------------------------------------------------------

    private static CrearMatchRequest peticion(boolean interesado) {
        return new CrearMatchRequest(ID_AVISTAMIENTO, TipoPublicacion.AVISTAMIENTO, interesado);
    }

    private static Avistamiento avistamiento() {
        return avistamiento(EstadoAlerta.ACTIVA);
    }

    private static Avistamiento avistamiento(EstadoAlerta estadoDeLaAlerta) {
        Alerta alerta = new Alerta();
        alerta.setId(55L);
        alerta.setEstado(estadoDeLaAlerta);
        alerta.setUsuario(usuario(ID_DUENYO_ALERTA));

        Avistamiento avistamiento = new Avistamiento();
        avistamiento.setId(ID_AVISTAMIENTO);
        avistamiento.setAlerta(alerta);
        avistamiento.setUsuario(usuario(ID_AUTOR_AVISTAMIENTO));
        return avistamiento;
    }

    private static Usuario usuario(Long id) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setNombre("Usuario " + id);
        usuario.setEmail("usuario" + id + "@test.es");
        usuario.setRol(Rol.CIUDADANO);
        usuario.setActivo(true);
        return usuario;
    }

    private static MatchRegistro registro(Long id) {
        MatchRegistro registro = new MatchRegistro();
        registro.setId(id);
        registro.setUsuario(usuario(ID_USUARIO));
        registro.setPublicacionId(ID_AVISTAMIENTO);
        registro.setTipoPublicacion(TipoPublicacion.AVISTAMIENTO);
        registro.setInteresado(true);
        registro.setCreatedAt(LocalDateTime.now());
        return registro;
    }

    private static Chat chat(Long id, MatchRegistro match) {
        Chat chat = new Chat();
        chat.setId(id);
        chat.setMatch(match);
        return chat;
    }

    /** Comprueba el destinatario por id, sin depender de equals de la entidad. */
    private static Usuario argThatTieneId(Long id) {
        return org.mockito.ArgumentMatchers.argThat(
                usuario -> usuario != null && id.equals(usuario.getId()));
    }
}
