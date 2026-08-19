package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.ChatDetalleResponse;
import com.rescateanimal.backend.dto.response.ChatResumenResponse;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.ChatAjenoException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Mensaje;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ChatRepository;
import com.rescateanimal.backend.repository.MensajeRepository;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Quién participa en un chat y qué ve (RF-12).
 *
 * Lo que más se prueba aquí es el límite de acceso: los dos participantes son
 * quien deslizó y quien publicó el avistamiento deslizado, y cualquier otro
 * usuario —incluido el dueño de la alerta, si no es ninguno de los dos— recibe
 * un 403.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    private static final Long ID_CHAT = 500L;
    private static final Long ID_MATCH = 100L;
    private static final Long ID_AVISTAMIENTO = 33L;
    private static final Long ID_ALERTA = 12L;

    /** Deslizó el avistamiento: participante A. */
    private static final Long ID_EMISOR = 7L;
    /** Publicó el avistamiento: participante B. */
    private static final Long ID_AUTOR = 8L;
    /** Dueño de la alerta que no participa en este chat concreto. */
    private static final Long ID_DUENYO_ALERTA = 9L;
    /** No pinta nada en esta conversación. */
    private static final Long ID_TERCERO = 99L;
    /** Cuenta de la protectora duena del animal: participante B en adopcion. */
    private static final Long ID_CUENTA_PROTECTORA = 20L;
    private static final Long ID_ANIMAL = 44L;

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private MensajeRepository mensajeRepository;
    @Mock
    private AvistamientoRepository avistamientoRepository;
    @Mock
    private AnimalAdopcionRepository animalAdopcionRepository;

    private ChatService chatService;

    @BeforeEach
    void prepararServicio() {
        chatService = new ChatService(chatRepository, mensajeRepository, avistamientoRepository,
                animalAdopcionRepository);

        when(chatRepository.buscarConMatch(ID_CHAT)).thenReturn(Optional.of(chat()));
        when(avistamientoRepository.buscarConAutorYAlerta(anyCollection()))
                .thenReturn(List.of(avistamiento()));
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Quién puede entrar")
    class Acceso {

        @Test
        @DisplayName("El emisor del match participa")
        void elEmisorParticipa() {
            ChatService.Participantes participantes =
                    chatService.exigirParticipante(ID_CHAT, ID_EMISOR);

            assertThat(participantes.incluye(ID_EMISOR)).isTrue();
            assertThat(participantes.otro(ID_EMISOR).getId()).isEqualTo(ID_AUTOR);
            assertThat(participantes.yo(ID_EMISOR).getId()).isEqualTo(ID_EMISOR);
        }

        @Test
        @DisplayName("El autor del avistamiento participa")
        void elAutorParticipa() {
            ChatService.Participantes participantes =
                    chatService.exigirParticipante(ID_CHAT, ID_AUTOR);

            assertThat(participantes.incluye(ID_AUTOR)).isTrue();
            assertThat(participantes.otro(ID_AUTOR).getId()).isEqualTo(ID_EMISOR);
            assertThat(participantes.yo(ID_AUTOR).getId()).isEqualTo(ID_AUTOR);
        }

        @Test
        @DisplayName("Un tercero cualquiera -> 403, no 404: el chat existe, no es suyo")
        void unTerceroNoParticipa() {
            assertThatThrownBy(() -> chatService.exigirParticipante(ID_CHAT, ID_TERCERO))
                    .isInstanceOf(ChatAjenoException.class)
                    .hasMessageContaining("No participas");
        }

        @Test
        @DisplayName("El dueño de la alerta tampoco entra por serlo: el chat es de los dos del match")
        void elDuenyoDeLaAlertaNoParticipaPorSerlo() {
            assertThatThrownBy(() -> chatService.exigirParticipante(ID_CHAT, ID_DUENYO_ALERTA))
                    .isInstanceOf(ChatAjenoException.class);
        }

        @Test
        @DisplayName("Chat inexistente -> 404")
        void chatInexistenteEs404() {
            when(chatRepository.buscarConMatch(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.exigirParticipante(404L, ID_EMISOR))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("esParticipante no lanza: un chat que no existe simplemente da false")
        void esParticipanteNoLanza() {
            when(chatRepository.buscarConMatch(404L)).thenReturn(Optional.empty());

            assertThat(chatService.esParticipante(404L, ID_EMISOR)).isFalse();
            assertThat(chatService.esParticipante(ID_CHAT, ID_TERCERO)).isFalse();
            assertThat(chatService.esParticipante(ID_CHAT, ID_EMISOR)).isTrue();
            assertThat(chatService.esParticipante(ID_CHAT, ID_AUTOR)).isTrue();
        }
    }

    @Nested
    @DisplayName("Detalle de la conversación")
    class Detalle {

        @Test
        @DisplayName("Cada participante ve al otro como interlocutor")
        void cadaUnoVeAlOtro() {
            assertThat(chatService.detalle(ID_CHAT, ID_EMISOR).getOtroParticipante().getId())
                    .isEqualTo(ID_AUTOR);
            assertThat(chatService.detalle(ID_CHAT, ID_AUTOR).getOtroParticipante().getId())
                    .isEqualTo(ID_EMISOR);
        }

        @Test
        @DisplayName("Trae el contexto de la publicación que originó el chat")
        void traeElContextoDeLaPublicacion() {
            ChatDetalleResponse detalle = chatService.detalle(ID_CHAT, ID_EMISOR);

            assertThat(detalle.getId()).isEqualTo(ID_CHAT);
            assertThat(detalle.getMatchId()).isEqualTo(ID_MATCH);
            assertThat(detalle.getAvistamientoId()).isEqualTo(ID_AVISTAMIENTO);
            assertThat(detalle.getAlertaId()).isEqualTo(ID_ALERTA);
            assertThat(detalle.getAlertaEstado()).isEqualTo("ACTIVA");
        }

        @Test
        @DisplayName("El interlocutor viaja sin email ni teléfono (RNF-10)")
        void elInterlocutorNoLlevaDatosPersonales() {
            ChatDetalleResponse detalle = chatService.detalle(ID_CHAT, ID_EMISOR);

            assertThat(detalle.getOtroParticipante().getNombre()).isEqualTo("Usuario " + ID_AUTOR);
            // AutorResponse solo expone id, nombre y foto: si alguien añadiera
            // email o teléfono, esta comprobación de forma lo delataría.
            assertThat(detalle.getOtroParticipante()).hasNoNullFieldsOrPropertiesExcept("fotoUrl");
        }

        @Test
        @DisplayName("Un tercero no ve el detalle")
        void unTerceroNoVeElDetalle() {
            assertThatThrownBy(() -> chatService.detalle(ID_CHAT, ID_TERCERO))
                    .isInstanceOf(ChatAjenoException.class);
        }
    }

    @Nested
    @DisplayName("Listado de conversaciones")
    class Listado {

        @Test
        @DisplayName("Sin chats no se consulta nada más")
        void sinChatsNoConsultaNadaMas() {
            when(chatRepository.buscarPorParticipante(ID_EMISOR, TipoPublicacion.AVISTAMIENTO, TipoPublicacion.ADOPCION))
                    .thenReturn(List.of());

            assertThat(chatService.listarMisChats(ID_EMISOR)).isEmpty();
            verifyNoInteractions(mensajeRepository);
        }

        @Test
        @DisplayName("Cada fila trae el interlocutor, el último mensaje y los no leídos")
        void cadaFilaTraeLoQuePintaLaLista() {
            when(chatRepository.buscarPorParticipante(ID_EMISOR, TipoPublicacion.AVISTAMIENTO, TipoPublicacion.ADOPCION))
                    .thenReturn(List.of(chat()));
            when(mensajeRepository.buscarUltimoDeCadaChat(List.of(ID_CHAT)))
                    .thenReturn(List.of(mensaje(3L, ID_AUTOR, "¿Sigue por allí?", false)));
            when(mensajeRepository.contarNoLeidosPorChat(List.of(ID_CHAT), ID_EMISOR))
                    .thenReturn(List.of(conteo(ID_CHAT, 2L)));

            List<ChatResumenResponse> chats = chatService.listarMisChats(ID_EMISOR);

            assertThat(chats).hasSize(1);
            assertThat(chats.get(0).getId()).isEqualTo(ID_CHAT);
            assertThat(chats.get(0).getOtroParticipante().getId()).isEqualTo(ID_AUTOR);
            assertThat(chats.get(0).getUltimoMensaje().getContenido()).isEqualTo("¿Sigue por allí?");
            assertThat(chats.get(0).getNoLeidos()).isEqualTo(2);
        }

        @Test
        @DisplayName("Un chat sin mensajes aparece igual, sin último mensaje y con cero no leídos")
        void unChatSinMensajesApareceIgual() {
            when(chatRepository.buscarPorParticipante(ID_EMISOR, TipoPublicacion.AVISTAMIENTO, TipoPublicacion.ADOPCION))
                    .thenReturn(List.of(chat()));
            when(mensajeRepository.buscarUltimoDeCadaChat(anyCollection())).thenReturn(List.of());
            when(mensajeRepository.contarNoLeidosPorChat(anyCollection(), anyLong()))
                    .thenReturn(List.of());

            List<ChatResumenResponse> chats = chatService.listarMisChats(ID_EMISOR);

            assertThat(chats).hasSize(1);
            assertThat(chats.get(0).getUltimoMensaje()).isNull();
            assertThat(chats.get(0).getNoLeidos()).isZero();
        }

        @Test
        @DisplayName("Se ordenan por actividad: el del mensaje más reciente, primero")
        void seOrdenanPorActividad() {
            Chat antiguo = chat();
            Chat reciente = chat();
            reciente.setId(501L);

            when(chatRepository.buscarPorParticipante(ID_EMISOR, TipoPublicacion.AVISTAMIENTO, TipoPublicacion.ADOPCION))
                    .thenReturn(List.of(antiguo, reciente));
            when(mensajeRepository.buscarUltimoDeCadaChat(anyCollection())).thenReturn(List.of(
                    mensajeEn(1L, antiguo, LocalDateTime.now().minusDays(3)),
                    mensajeEn(2L, reciente, LocalDateTime.now().minusMinutes(1))));
            when(mensajeRepository.contarNoLeidosPorChat(anyCollection(), anyLong()))
                    .thenReturn(List.of());

            List<ChatResumenResponse> chats = chatService.listarMisChats(ID_EMISOR);

            assertThat(chats).extracting(ChatResumenResponse::getId).containsExactly(501L, ID_CHAT);
        }

        @Test
        @DisplayName("Se lista tanto siendo emisor como siendo autor del avistamiento")
        void seListaDesdeLosDosLados() {
            when(chatRepository.buscarPorParticipante(anyLong(), any(), any())).thenReturn(List.of(chat()));
            when(mensajeRepository.buscarUltimoDeCadaChat(anyCollection())).thenReturn(List.of());
            when(mensajeRepository.contarNoLeidosPorChat(anyCollection(), anyLong()))
                    .thenReturn(List.of());

            assertThat(chatService.listarMisChats(ID_EMISOR).get(0).getOtroParticipante().getId())
                    .isEqualTo(ID_AUTOR);
            assertThat(chatService.listarMisChats(ID_AUTOR).get(0).getOtroParticipante().getId())
                    .isEqualTo(ID_EMISOR);
        }
    }

    /**
     * Conversaciones nacidas de un match de adopción (RF-09).
     *
     * Lo que hay que fijar aquí es que el segundo participante ya no es el autor
     * de un avistamiento sino la CUENTA de la protectora, y que el tipo del
     * match decide en qué tabla se busca la publicación: publicacion_id apunta a
     * dos tablas distintas y el mismo número existe en ambas.
     */
    @Nested
    @DisplayName("Conversaciones de adopción (RF-09)")
    class ChatDeAdopcion {

        @BeforeEach
        void prepararChatDeAdopcion() {
            when(chatRepository.buscarConMatch(ID_CHAT))
                    .thenReturn(Optional.of(chatDeAdopcion()));
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal()));
        }

        @Test
        @DisplayName("Los participantes son quien deslizó y la cuenta de la protectora")
        void participanElCiudadanoYLaProtectora() {
            ChatService.Participantes participantes =
                    chatService.exigirParticipante(ID_CHAT, ID_EMISOR);

            assertThat(participantes.incluye(ID_EMISOR)).isTrue();
            assertThat(participantes.incluye(ID_CUENTA_PROTECTORA)).isTrue();
            assertThat(participantes.otro(ID_EMISOR).getId()).isEqualTo(ID_CUENTA_PROTECTORA);
        }

        @Test
        @DisplayName("El autor de un avistamiento cualquiera no entra en un chat de adopción")
        void unTerceroNoParticipa() {
            assertThatThrownBy(() -> chatService.exigirParticipante(ID_CHAT, ID_TERCERO))
                    .isInstanceOf(ChatAjenoException.class);
        }

        @Test
        @DisplayName("El detalle trae el animal y su protectora, no el avistamiento")
        void elDetalleTraeElAnimal() {
            ChatDetalleResponse detalle = chatService.detalle(ID_CHAT, ID_EMISOR);

            assertThat(detalle.getTipoPublicacion()).isEqualTo(TipoPublicacion.ADOPCION);
            assertThat(detalle.getAnimalId()).isEqualTo(ID_ANIMAL);
            assertThat(detalle.getAnimalNombre()).isEqualTo("Luna");
            assertThat(detalle.getAnimalEstado()).isEqualTo("DISPONIBLE");
            assertThat(detalle.getProtectoraNombre()).isEqualTo("Protectora de prueba");

            // Los campos del otro tipo quedan sin rellenar: el DTO los omite.
            assertThat(detalle.getAvistamientoId()).isNull();
            assertThat(detalle.getAlertaId()).isNull();
        }

        @Test
        @DisplayName("El tipo decide la tabla: no se busca el id como avistamiento")
        void elTipoDecideLaTabla() {
            chatService.detalle(ID_CHAT, ID_EMISOR);

            verifyNoInteractions(avistamientoRepository);
        }

        @Test
        @DisplayName("Animal retirado del catálogo -> 404 del chat, no un 500")
        void animalRetiradoDevuelve404() {
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> chatService.detalle(ID_CHAT, ID_EMISOR))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("El listado mezcla los dos tipos y resuelve cada uno en su tabla")
        void elListadoMezclaLosDosTipos() {
            Chat deAvistamiento = chat();
            Chat deAdopcion = chatDeAdopcion();
            deAdopcion.setId(501L);

            when(chatRepository.buscarPorParticipante(
                    ID_EMISOR, TipoPublicacion.AVISTAMIENTO, TipoPublicacion.ADOPCION))
                    .thenReturn(List.of(deAvistamiento, deAdopcion));
            when(animalAdopcionRepository.buscarConProtectoraPorIds(anyCollection()))
                    .thenReturn(List.of(animal()));
            when(mensajeRepository.buscarUltimoDeCadaChat(anyCollection())).thenReturn(List.of());
            when(mensajeRepository.contarNoLeidosPorChat(anyCollection(), anyLong()))
                    .thenReturn(List.of());

            List<ChatResumenResponse> chats = chatService.listarMisChats(ID_EMISOR);

            assertThat(chats).hasSize(2);
            assertThat(chats).extracting(chat -> chat.getOtroParticipante().getId())
                    .containsExactlyInAnyOrder(ID_AUTOR, ID_CUENTA_PROTECTORA);
        }

        @Test
        @DisplayName("Un chat cuyo animal ya no existe se omite del listado, no lo tumba")
        void elChatSinAnimalSeOmite() {
            when(chatRepository.buscarPorParticipante(
                    ID_EMISOR, TipoPublicacion.AVISTAMIENTO, TipoPublicacion.ADOPCION))
                    .thenReturn(List.of(chatDeAdopcion()));
            when(animalAdopcionRepository.buscarConProtectoraPorIds(anyCollection()))
                    .thenReturn(List.of());
            when(mensajeRepository.buscarUltimoDeCadaChat(anyCollection())).thenReturn(List.of());
            when(mensajeRepository.contarNoLeidosPorChat(anyCollection(), anyLong()))
                    .thenReturn(List.of());

            assertThat(chatService.listarMisChats(ID_EMISOR)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Histórico de mensajes")
    class Historico {

        @Test
        @DisplayName("El orden lo fija el servidor: más reciente primero, con desempate por id")
        void elOrdenLoFijaElServidor() {
            when(mensajeRepository.buscarPorChat(eq(ID_CHAT), any()))
                    .thenReturn(new PageImpl<>(List.of(mensaje(1L, ID_EMISOR, "hola", true))));

            chatService.historico(ID_CHAT, ID_EMISOR,
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "contenido")));

            ArgumentCaptor<Pageable> peticion = ArgumentCaptor.forClass(Pageable.class);
            verify(mensajeRepository).buscarPorChat(eq(ID_CHAT), peticion.capture());

            assertThat(peticion.getValue().getSort())
                    .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        }

        @Test
        @DisplayName("El tamaño de página se recorta al máximo admitido")
        void elTamanyoSeRecorta() {
            when(mensajeRepository.buscarPorChat(eq(ID_CHAT), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            chatService.historico(ID_CHAT, ID_EMISOR, PageRequest.of(0, 5000));

            ArgumentCaptor<Pageable> peticion = ArgumentCaptor.forClass(Pageable.class);
            verify(mensajeRepository).buscarPorChat(eq(ID_CHAT), peticion.capture());

            assertThat(peticion.getValue().getPageSize())
                    .isEqualTo(ChatService.MAXIMO_MENSAJES_POR_PAGINA);
        }

        @Test
        @DisplayName("Devuelve la envoltura documentada, no el Page de Spring Data")
        void devuelveLaEnvolturaDocumentada() {
            when(mensajeRepository.buscarPorChat(eq(ID_CHAT), any())).thenReturn(
                    new PageImpl<>(List.of(mensaje(1L, ID_AUTOR, "hola", false)),
                            PageRequest.of(0, 20), 1));

            PaginaResponse<MensajeResponse> pagina =
                    chatService.historico(ID_CHAT, ID_EMISOR, PageRequest.of(0, 20));

            assertThat(pagina.getContent()).hasSize(1);
            assertThat(pagina.getContent().get(0).getContenido()).isEqualTo("hola");
            assertThat(pagina.getContent().get(0).getChatId()).isEqualTo(ID_CHAT);
            assertThat(pagina.getPage()).isZero();
            assertThat(pagina.getSize()).isEqualTo(20);
            assertThat(pagina.getTotalElements()).isEqualTo(1);
            assertThat(pagina.isLast()).isTrue();
        }

        @Test
        @DisplayName("Un tercero no lee el histórico, y ni siquiera se consulta")
        void unTerceroNoLeeElHistorico() {
            assertThatThrownBy(() -> chatService.historico(ID_CHAT, ID_TERCERO, PageRequest.of(0, 20)))
                    .isInstanceOf(ChatAjenoException.class);

            verify(mensajeRepository, org.mockito.Mockito.never()).buscarPorChat(anyLong(), any());
        }
    }

    // ------------------------------------------------------------------

    private static Chat chat() {
        Chat chat = new Chat();
        chat.setId(ID_CHAT);
        chat.setMatch(match());
        chat.setCreatedAt(LocalDateTime.now().minusDays(1));
        return chat;
    }

    /** Chat nacido de un match de adopción: el mismo id de chat, otro origen. */
    private static Chat chatDeAdopcion() {
        MatchRegistro match = new MatchRegistro();
        match.setId(ID_MATCH);
        match.setUsuario(usuario(ID_EMISOR));
        match.setPublicacionId(ID_ANIMAL);
        match.setTipoPublicacion(TipoPublicacion.ADOPCION);
        match.setInteresado(true);

        Chat chat = new Chat();
        chat.setId(ID_CHAT);
        chat.setMatch(match);
        chat.setCreatedAt(LocalDateTime.now().minusDays(1));
        return chat;
    }

    private static AnimalAdopcion animal() {
        Protectora protectora = new Protectora();
        protectora.setId(70L);
        protectora.setNombreOrganizacion("Protectora de prueba");
        protectora.setUsuario(usuario(ID_CUENTA_PROTECTORA));

        AnimalAdopcion animal = new AnimalAdopcion();
        animal.setId(ID_ANIMAL);
        animal.setProtectora(protectora);
        animal.setNombre("Luna");
        animal.setEspecie("perro");
        animal.setFotoUrl("https://cdn.test/luna.jpg");
        animal.setEstado(EstadoAnimal.DISPONIBLE);
        return animal;
    }

    private static MatchRegistro match() {
        MatchRegistro match = new MatchRegistro();
        match.setId(ID_MATCH);
        match.setUsuario(usuario(ID_EMISOR));
        match.setPublicacionId(ID_AVISTAMIENTO);
        match.setTipoPublicacion(TipoPublicacion.AVISTAMIENTO);
        match.setInteresado(true);
        return match;
    }

    private static Avistamiento avistamiento() {
        Alerta alerta = new Alerta();
        alerta.setId(ID_ALERTA);
        alerta.setUsuario(usuario(ID_DUENYO_ALERTA));
        alerta.setEspecie("perro");
        alerta.setEstado(EstadoAlerta.ACTIVA);

        Avistamiento avistamiento = new Avistamiento();
        avistamiento.setId(ID_AVISTAMIENTO);
        avistamiento.setUsuario(usuario(ID_AUTOR));
        avistamiento.setAlerta(alerta);
        avistamiento.setFotoUrl("https://fotos/avistamiento.jpg");
        avistamiento.setDescripcion("Lo he visto en el parque");
        return avistamiento;
    }

    private static Mensaje mensaje(Long id, Long emisorId, String contenido, boolean leido) {
        Mensaje mensaje = new Mensaje();
        mensaje.setId(id);
        mensaje.setChat(chat());
        mensaje.setEmisor(usuario(emisorId));
        mensaje.setContenido(contenido);
        mensaje.setLeido(leido);
        mensaje.setCreatedAt(LocalDateTime.now());
        return mensaje;
    }

    private static Mensaje mensajeEn(Long id, Chat chat, LocalDateTime cuando) {
        Mensaje mensaje = mensaje(id, ID_AUTOR, "texto", false);
        mensaje.setChat(chat);
        mensaje.setCreatedAt(cuando);
        return mensaje;
    }

    private static MensajeRepository.ConteoNoLeidos conteo(Long chatId, long total) {
        return new MensajeRepository.ConteoNoLeidos() {
            @Override
            public Long getChatId() {
                return chatId;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
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
}
