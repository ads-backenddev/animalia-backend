package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.EnviarMensajeRequest;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.exception.ChatAjenoException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Chat;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Mensaje;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.MensajeRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Envío de mensajes y marcado de leídos (RF-12).
 *
 * Las dos reglas que más se comprueban aquí son las que no se ven desde fuera:
 * que la difusión por WebSocket espera a que la transacción esté confirmada, y
 * que "leídos" nunca toca los mensajes propios.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MensajeServiceTest {

    private static final Long ID_CHAT = 500L;
    private static final Long ID_EMISOR = 7L;
    private static final Long ID_AUTOR = 8L;
    private static final Long ID_TERCERO = 99L;
    private static final String DESTINO = "/topic/chat/" + ID_CHAT;

    @Mock
    private MensajeRepository mensajeRepository;
    @Mock
    private ChatService chatService;
    @Mock
    private SimpMessagingTemplate mensajeria;

    private MensajeService mensajeService;

    @BeforeEach
    void prepararServicio() {
        mensajeService = new MensajeService(mensajeRepository, chatService, mensajeria);

        when(chatService.exigirParticipante(ID_CHAT, ID_EMISOR)).thenReturn(participantes());
        when(chatService.exigirParticipante(ID_CHAT, ID_AUTOR)).thenReturn(participantes());
        when(chatService.exigirParticipante(ID_CHAT, ID_TERCERO))
                .thenThrow(new ChatAjenoException("No participas en esta conversación"));

        when(mensajeRepository.save(any(Mensaje.class))).thenAnswer(invocacion -> {
            Mensaje mensaje = invocacion.getArgument(0);
            mensaje.setId(900L);
            mensaje.setCreatedAt(Instant.now());
            return mensaje;
        });
    }

    @AfterEach
    void limpiarTransaccion() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Envío")
    class Envio {

        @Test
        @DisplayName("Guarda el mensaje a nombre de quien lo manda y lo difunde en su chat")
        void guardaYDifunde() {
            MensajeResponse respuesta = mensajeService.enviar(ID_CHAT, ID_EMISOR, peticion("hola"));

            ArgumentCaptor<Mensaje> guardado = ArgumentCaptor.forClass(Mensaje.class);
            verify(mensajeRepository).save(guardado.capture());
            assertThat(guardado.getValue().getEmisor().getId()).isEqualTo(ID_EMISOR);
            assertThat(guardado.getValue().getChat().getId()).isEqualTo(ID_CHAT);
            assertThat(guardado.getValue().getContenido()).isEqualTo("hola");

            // Un mensaje nace sin leer: lo estará cuando el otro lo marque.
            assertThat(guardado.getValue().getLeido()).isFalse();

            verify(mensajeria).convertAndSend(DESTINO, respuesta);
            assertThat(respuesta.getId()).isEqualTo(900L);
            assertThat(respuesta.getChatId()).isEqualTo(ID_CHAT);
            assertThat(respuesta.getEmisor().getId()).isEqualTo(ID_EMISOR);
        }

        @Test
        @DisplayName("El emisor sale del token, no del cuerpo: los dos lados escriben a su nombre")
        void elEmisorSaleDelToken() {
            mensajeService.enviar(ID_CHAT, ID_AUTOR, peticion("respondo"));

            ArgumentCaptor<Mensaje> guardado = ArgumentCaptor.forClass(Mensaje.class);
            verify(mensajeRepository).save(guardado.capture());
            assertThat(guardado.getValue().getEmisor().getId()).isEqualTo(ID_AUTOR);
        }

        @Test
        @DisplayName("Un tercero no escribe: ni se guarda ni se difunde nada")
        void unTerceroNoEscribe() {
            assertThatThrownBy(() -> mensajeService.enviar(ID_CHAT, ID_TERCERO, peticion("cotilleo")))
                    .isInstanceOf(ChatAjenoException.class);

            verifyNoInteractions(mensajeRepository);
            verifyNoInteractions(mensajeria);
        }

        @Test
        @DisplayName("Se recortan los espacios sobrantes del texto")
        void seRecortanLosEspacios() {
            mensajeService.enviar(ID_CHAT, ID_EMISOR, peticion("  con espacios  "));

            ArgumentCaptor<Mensaje> guardado = ArgumentCaptor.forClass(Mensaje.class);
            verify(mensajeRepository).save(guardado.capture());
            assertThat(guardado.getValue().getContenido()).isEqualTo("con espacios");
        }

        @Test
        @DisplayName("Un fallo de la mensajería no tumba el envío: el mensaje ya está guardado")
        void unFalloDeLaMensajeriaNoTumbaElEnvio() {
            org.mockito.Mockito.doThrow(new IllegalStateException("broker caído"))
                    .when(mensajeria).convertAndSend(anyString(), any(Object.class));

            MensajeResponse respuesta = mensajeService.enviar(ID_CHAT, ID_EMISOR, peticion("hola"));

            assertThat(respuesta.getId()).isEqualTo(900L);
            verify(mensajeRepository).save(any(Mensaje.class));
        }
    }

    @Nested
    @DisplayName("La difusión espera a que la transacción esté confirmada")
    class Difusion {

        @BeforeEach
        void abrirTransaccion() {
            TransactionSynchronizationManager.initSynchronization();
        }

        @Test
        @DisplayName("Con transacción abierta no se difunde todavía: un rollback dejaría un mensaje fantasma")
        void conTransaccionAbiertaNoSeDifundeTodavia() {
            mensajeService.enviar(ID_CHAT, ID_EMISOR, peticion("hola"));

            verify(mensajeRepository).save(any(Mensaje.class));
            verify(mensajeria, never()).convertAndSend(anyString(), any(Object.class));
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        }

        @Test
        @DisplayName("Y sale en cuanto la transacción confirma")
        void saleAlConfirmar() {
            MensajeResponse respuesta = mensajeService.enviar(ID_CHAT, ID_EMISOR, peticion("hola"));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sincronizacion -> sincronizacion.afterCommit());

            verify(mensajeria).convertAndSend(DESTINO, respuesta);
        }

        @Test
        @DisplayName("Si la transacción no confirma, el mensaje nunca se difunde")
        void sinConfirmarNoSeDifunde() {
            mensajeService.enviar(ID_CHAT, ID_EMISOR, peticion("hola"));

            // Se descarta la transacción sin llamar a afterCommit, que es lo que
            // hace Spring en un rollback.
            TransactionSynchronizationManager.clearSynchronization();

            verify(mensajeria, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    @Nested
    @DisplayName("Marcado de leídos")
    class Leidos {

        @Test
        @DisplayName("Solo marca los del otro participante, nunca los propios")
        void soloMarcaLosDelOtro() {
            when(mensajeRepository.marcarRecibidosComoLeidos(ID_CHAT, ID_EMISOR)).thenReturn(3);

            assertThat(mensajeService.marcarLeidos(ID_CHAT, ID_EMISOR)).isEqualTo(3);

            // El id que se pasa es el de quien marca, y en la consulta es el
            // emisor EXCLUIDO: es la garantía de que no se tocan los propios.
            verify(mensajeRepository).marcarRecibidosComoLeidos(ID_CHAT, ID_EMISOR);
        }

        @Test
        @DisplayName("Cada participante marca los del otro, no los mismos mensajes")
        void cadaUnoMarcaLosDelOtro() {
            mensajeService.marcarLeidos(ID_CHAT, ID_EMISOR);
            mensajeService.marcarLeidos(ID_CHAT, ID_AUTOR);

            verify(mensajeRepository).marcarRecibidosComoLeidos(ID_CHAT, ID_EMISOR);
            verify(mensajeRepository).marcarRecibidosComoLeidos(ID_CHAT, ID_AUTOR);
        }

        @Test
        @DisplayName("Un tercero no marca nada")
        void unTerceroNoMarcaNada() {
            assertThatThrownBy(() -> mensajeService.marcarLeidos(ID_CHAT, ID_TERCERO))
                    .isInstanceOf(ChatAjenoException.class);

            verify(mensajeRepository, never()).marcarRecibidosComoLeidos(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Marcar dos veces es inofensivo: la segunda no encuentra nada que marcar")
        void marcarDosVecesEsInofensivo() {
            when(mensajeRepository.marcarRecibidosComoLeidos(ID_CHAT, ID_EMISOR)).thenReturn(3, 0);

            assertThat(mensajeService.marcarLeidos(ID_CHAT, ID_EMISOR)).isEqualTo(3);
            assertThat(mensajeService.marcarLeidos(ID_CHAT, ID_EMISOR)).isZero();
        }
    }

    // ------------------------------------------------------------------

    private static EnviarMensajeRequest peticion(String contenido) {
        return new EnviarMensajeRequest(contenido);
    }

    private static ChatService.Participantes participantes() {
        Chat chat = new Chat();
        chat.setId(ID_CHAT);
        chat.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));

        MatchRegistro match = new MatchRegistro();
        match.setId(100L);
        match.setUsuario(usuario(ID_EMISOR));
        match.setPublicacionId(33L);
        match.setTipoPublicacion(TipoPublicacion.AVISTAMIENTO);
        match.setInteresado(true);
        chat.setMatch(match);

        Alerta alerta = new Alerta();
        alerta.setId(12L);
        alerta.setUsuario(usuario(9L));
        alerta.setEstado(EstadoAlerta.ACTIVA);

        Avistamiento avistamiento = new Avistamiento();
        avistamiento.setId(33L);
        avistamiento.setUsuario(usuario(ID_AUTOR));
        avistamiento.setAlerta(alerta);

        return new ChatService.Participantes(chat,
                new ChatService.PublicacionDelChat.DeAvistamiento(avistamiento), usuario(ID_EMISOR));
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
