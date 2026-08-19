package com.rescateanimal.backend.security;

import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.service.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Autorización de las suscripciones a /topic/chat/{chatId} (RF-12).
 *
 * Es la pieza que impide escuchar conversaciones ajenas adivinando su id, así
 * que lo que se prueba aquí no es que el camino feliz funcione, sino que todo
 * lo que no sea exactamente el camino feliz se rechaza.
 *
 * El grupo de los comodines merece explicación: el bróker no reparte comparando
 * cadenas sino con un AntPathMatcher, de modo que una suscripción a
 * /topic/chat/* recibe lo que se publique en /topic/chat/42. Un filtro que solo
 * mire "¿es esto /topic/chat/<número>?" y deje pasar lo que no reconoce se
 * salta entero. La última prueba lo comprueba contra el registro de
 * suscripciones de verdad, no contra la idea que tenemos de él.
 */
@ExtendWith(MockitoExtension.class)
class ChatSuscripcionInterceptorTest {

    private static final Long ID_USUARIO = 7L;
    private static final Long ID_CHAT = 42L;

    @Mock
    private ChatService chatService;
    @Mock
    private MessageChannel canal;

    @InjectMocks
    private ChatSuscripcionInterceptor interceptor;

    @Nested
    @DisplayName("Suscripción a un chat concreto")
    class ChatConcreto {

        @Test
        @DisplayName("Participante -> pasa")
        void participantePasa() {
            when(chatService.esParticipante(ID_CHAT, ID_USUARIO)).thenReturn(true);

            Message<?> resultado = interceptor.preSend(
                    subscribe("/topic/chat/42", autenticado(ID_USUARIO)), canal);

            assertThat(resultado).isNotNull();
        }

        @Test
        @DisplayName("No participante -> rechazo")
        void noParticipanteEsRechazado() {
            when(chatService.esParticipante(ID_CHAT, ID_USUARIO)).thenReturn(false);

            assertThatThrownBy(() -> interceptor.preSend(
                    subscribe("/topic/chat/42", autenticado(ID_USUARIO)), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("No participas");
        }

        @Test
        @DisplayName("Chat inexistente -> mismo rechazo que el ajeno, sin delatar que no existe")
        void chatInexistenteSeRechazaIgual() {
            when(chatService.esParticipante(anyLong(), any())).thenReturn(false);

            assertThatThrownBy(() -> interceptor.preSend(
                    subscribe("/topic/chat/999999", autenticado(ID_USUARIO)), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("No participas");
        }

        @Test
        @DisplayName("Sesión sin Principal -> rechazo antes de consultar nada")
        void sinPrincipalEsRechazado() {
            assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/chat/42", null), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("sin sesión autenticada");

            verify(chatService, never()).esParticipante(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Destinos con comodín")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class Comodines {

        @ParameterizedTest(name = "{0} -> rechazado")
        @ValueSource(strings = {
                "/topic/chat/*",
                "/topic/chat/**",
                "/topic/chat/4?",
                "/topic/**",
                "/topic/*/42",
                "/**"
        })
        @DisplayName("Un patrón alcanza chats ajenos aunque no case con la regex, así que se deniega")
        void losComodinesSeRechazan(String destino) {
            assertThatThrownBy(() -> interceptor.preSend(destino(destino), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("comodín");

            verify(chatService, never()).esParticipante(anyLong(), any());
        }

        @Test
        @DisplayName("El registro real del bróker confirma por qué: /topic/chat/* recibiría el chat 42")
        void elBrokerRepartiriaPorPatron() {
            DefaultSubscriptionRegistry registro = new DefaultSubscriptionRegistry();
            registro.registerSubscription(destino("/topic/chat/*"));

            StompHeaderAccessor difusion = StompHeaderAccessor.create(StompCommand.MESSAGE);
            difusion.setDestination("/topic/chat/42");

            assertThat(registro.findSubscriptions(
                    MessageBuilder.createMessage(new byte[0], difusion.getMessageHeaders())))
                    .as("si el bróker no repartiera por patrón, este rechazo sobraría")
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Destinos de chat mal formados")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class MalFormados {

        @ParameterizedTest(name = "{0} -> rechazado")
        @ValueSource(strings = {
                "/topic/chat/abc",
                "/topic/chat/42/",
                "/topic/chat/42/extra",
                "/topic/chat/",
                "/topic/chat",
                "/topic/chat/-1",
                "/topic/chat/99999999999999999999"
        })
        @DisplayName("Dentro de /topic/chat se deniega por defecto, no se deja pasar lo no reconocido")
        void loNoReconocidoSeDeniega(String destino) {
            assertThatThrownBy(() -> interceptor.preSend(destino(destino), canal))
                    .isInstanceOf(MessagingException.class);

            verify(chatService, never()).esParticipante(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Destinos ajenos al chat")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class FueraDelChat {

        @ParameterizedTest(name = "{0} -> pasa sin comprobación")
        @ValueSource(strings = {"/topic/mapa", "/user/queue/notificaciones", "/user/queue/errores"})
        @DisplayName("Su autorización es estar autenticado, y eso ya se resolvió en el handshake")
        void otrosDestinosPasan(String destino) {
            assertThatCode(() -> interceptor.preSend(destino(destino), canal)).doesNotThrowAnyException();

            verify(chatService, never()).esParticipante(anyLong(), any());
        }

        @Test
        @DisplayName("Una trama que no es SUBSCRIBE ni se mira")
        void otrasTramasNoSeInspeccionan() {
            StompHeaderAccessor accesor = StompHeaderAccessor.create(StompCommand.SEND);
            accesor.setDestination("/app/chat/42/enviar");

            assertThatCode(() -> interceptor.preSend(
                    MessageBuilder.createMessage(new byte[0], accesor.getMessageHeaders()), canal))
                    .doesNotThrowAnyException();

            verify(chatService, never()).esParticipante(anyLong(), any());
        }
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private static Message<?> destino(String destino) {
        return subscribe(destino, autenticado(ID_USUARIO));
    }

    private static Message<?> subscribe(String destino, Principal usuario) {
        StompHeaderAccessor accesor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accesor.setDestination(destino);
        accesor.setSessionId("sesion-1");
        accesor.setSubscriptionId("sub-0");
        accesor.setUser(usuario);
        return MessageBuilder.createMessage(new byte[0], accesor.getMessageHeaders());
    }

    /** El Principal tiene la forma que le da UsuarioHandshakeHandler. */
    private static Principal autenticado(Long id) {
        UsuarioPrincipal usuario = new UsuarioPrincipal(
                id, "ana@test.es", "$2a$10$hash", Rol.CIUDADANO, true, null);
        return new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities());
    }
}
