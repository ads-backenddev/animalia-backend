package com.rescateanimal.backend.security;

import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.service.ChatService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Autorización de las suscripciones a /topic/chat/{chatId} (RF-12) y
 * revalidación de la sesión en cada SUBSCRIBE.
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
 *
 * El grupo de la revalidación cubre la otra mitad: el handshake comprueba el
 * token UNA vez, al abrir la conexión, así que sin volver a mirarlo en cada
 * suscripción una sesión abierta sobreviviría al logout, al bloqueo por un
 * administrador y al cambio de contraseña. Todas esas pruebas dan por bueno el
 * Principal de la sesión —que es una foto del handshake— y cambian solo lo que
 * dice la base de datos ahora, que es justo el desfase que hay que detectar.
 */
@ExtendWith(MockitoExtension.class)
class ChatSuscripcionInterceptorTest {

    private static final Long ID_USUARIO = 7L;
    private static final Long ID_CHAT = 42L;
    private static final String EMAIL = "ana@test.es";
    private static final String JTI = "jti-1";

    @Mock
    private ChatService chatService;
    @Mock
    private TokenRevocadoService tokenRevocadoService;
    @Mock
    private UserDetailsServiceImpl userDetailsService;
    @Mock
    private MessageChannel canal;

    @InjectMocks
    private ChatSuscripcionInterceptor interceptor;

    /**
     * Por defecto, la sesión sigue siendo válida: cuenta activa, token sin
     * revocar y sin cambios de contraseña. Se declara lenient porque las pruebas
     * que rechazan antes de llegar a la revalidación —sin Principal, o una trama
     * que no es SUBSCRIBE— no llegan a usarlo, y eso no es un stub sobrante sino
     * el punto de partida común.
     */
    @BeforeEach
    void sesionValidaPorDefecto() {
        lenient().when(tokenRevocadoService.estaRevocado(anyString())).thenReturn(false);
        lenient().when(userDetailsService.loadUserByUsername(EMAIL))
                .thenReturn(usuario(true, null));
    }

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
        @DisplayName("Un SEND con la sesión en regla pasa, y no se le aplican las reglas de destino")
        void elEnvioConSesionValidaPasa() {
            // Las reglas de /topic/chat/{id} son para suscribirse. A quién
            // pertenece el chat al que se escribe lo comprueba MensajeService.
            assertThatCode(() -> interceptor.preSend(envio("/app/chat/42/enviar"), canal))
                    .doesNotThrowAnyException();

            verify(chatService, never()).esParticipante(anyLong(), any());
        }

        @Test
        @DisplayName("Una trama que no es ni SUBSCRIBE ni SEND ni se mira")
        void otrasTramasNoSeInspeccionan() {
            StompHeaderAccessor accesor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
            accesor.setSessionId("sesion-1");

            assertThatCode(() -> interceptor.preSend(
                    MessageBuilder.createMessage(new byte[0], accesor.getMessageHeaders()), canal))
                    .doesNotThrowAnyException();

            verify(chatService, never()).esParticipante(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Revalidación de la sesión en cada suscripción")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class RevalidacionDeLaSesion {

        @Test
        @DisplayName("Un usuario bloqueado no puede suscribirse a un chat nuevo aunque su token siga siendo válido")
        void elBloqueadoNoEntraEnUnChatNuevo() {
            // El Principal de la sesión sigue diciendo que la cuenta está activa,
            // porque es la foto del handshake; la base de datos ya dice que no. Es
            // exactamente lo que pasa cuando un administrador bloquea a alguien que
            // tenía el WebSocket abierto (RF-11).
            when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(usuario(false, null));
            when(chatService.esParticipante(anyLong(), any())).thenReturn(true);

            assertThatThrownBy(() -> interceptor.preSend(
                    subscribe("/topic/chat/42", autenticado(ID_USUARIO)), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("bloqueada");

            // Y se corta antes de mirar siquiera si participa: el bloqueo no depende
            // de a qué se quiera suscribir.
            verify(chatService, never()).esParticipante(anyLong(), any());
        }

        @Test
        @DisplayName("Al bloqueado tampoco le vale otro destino, como el mapa")
        void elBloqueadoNoEntraEnNingunDestino() {
            when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(usuario(false, null));

            assertThatThrownBy(() -> interceptor.preSend(destino("/topic/mapa"), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("bloqueada");
        }

        @Test
        @DisplayName("La cuenta se recarga de la base de datos, no se reutiliza la del handshake")
        void laCuentaSeRecargaEnCadaSuscripcion() {
            when(chatService.esParticipante(anyLong(), any())).thenReturn(true);

            interceptor.preSend(subscribe("/topic/chat/42", autenticado(ID_USUARIO)), canal);
            interceptor.preSend(subscribe("/topic/chat/42", autenticado(ID_USUARIO)), canal);

            verify(userDetailsService, times(2)).loadUserByUsername(EMAIL);
        }

        @Test
        @DisplayName("Token revocado por un logout -> rechazo (RF-16)")
        void elTokenRevocadoNoSirve() {
            when(tokenRevocadoService.estaRevocado(JTI)).thenReturn(true);

            assertThatThrownBy(() -> interceptor.preSend(destino("/topic/chat/42"), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("ya no es válida");

            verify(chatService, never()).esParticipante(anyLong(), any());
        }

        @Test
        @DisplayName("Token caducado -> rechazo, aunque la conexión lleve horas abierta")
        void elTokenCaducadoNoSirve() {
            Message<?> mensaje = subscribe("/topic/chat/42", autenticado(ID_USUARIO),
                    sesionCon(new JwtHandshakeInterceptor.DatosToken(
                            JTI, Instant.now().minusSeconds(60), Instant.now().minusSeconds(3660))));

            assertThatThrownBy(() -> interceptor.preSend(mensaje, canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("caducado");
        }

        @Test
        @DisplayName("Token anterior al último cambio de contraseña -> rechazo (RF-17)")
        void elTokenAnteriorAlCambioDePasswordNoSirve() {
            when(userDetailsService.loadUserByUsername(EMAIL))
                    .thenReturn(usuario(true, Instant.now()));

            Message<?> mensaje = subscribe("/topic/chat/42", autenticado(ID_USUARIO),
                    sesionCon(new JwtHandshakeInterceptor.DatosToken(
                            JTI, Instant.now().plusSeconds(3600), Instant.now().minusSeconds(3600))));

            assertThatThrownBy(() -> interceptor.preSend(mensaje, canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("contraseña ha cambiado");
        }

        @Test
        @DisplayName("Cuenta borrada después del handshake -> rechazo limpio, no un error del canal")
        void laCuentaBorradaSeRechazaSinRomper() {
            when(userDetailsService.loadUserByUsername(EMAIL))
                    .thenThrow(new UsernameNotFoundException("no existe"));

            assertThatThrownBy(() -> interceptor.preSend(destino("/topic/chat/42"), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("ya no es válida");
        }

        @Test
        @DisplayName("Sesión sin datos del token -> se rechaza, no se deja pasar por no poder comprobar")
        void sinDatosDelTokenSeRechaza() {
            // Solo se llegaría aquí con una sesión abierta antes de que esto
            // existiera, o si alguien retirase JwtHandshakeInterceptor de la cadena.
            // En los dos casos, no poder verificar una sesión es motivo para no
            // fiarse de ella: fallar cerrado, no abierto.
            Message<?> mensaje = subscribe("/topic/chat/42", autenticado(ID_USUARIO), new HashMap<>());

            assertThatThrownBy(() -> interceptor.preSend(mensaje, canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Vuelve a conectarte");

            verify(chatService, never()).esParticipante(anyLong(), any());
        }

        @Test
        @DisplayName("Sesión sin atributos siquiera -> mismo rechazo, sin NullPointerException")
        void sinAtributosDeSesionSeRechaza() {
            Message<?> mensaje = subscribe("/topic/chat/42", autenticado(ID_USUARIO), null);

            assertThatThrownBy(() -> interceptor.preSend(mensaje, canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Vuelve a conectarte");
        }

        @Test
        @DisplayName("Con la sesión en regla, la suscripción sigue su curso normal")
        void laSesionValidaNoEstorba() {
            when(chatService.esParticipante(ID_CHAT, ID_USUARIO)).thenReturn(true);

            assertThat(interceptor.preSend(destino("/topic/chat/42"), canal)).isNotNull();
        }

        @Test
        @DisplayName("Un usuario bloqueado tampoco puede ESCRIBIR en un chat que ya tenía abierto")
        void elBloqueadoNoPuedeEscribir() {
            // ESTE ERA EL AGUJERO. Solo se revalidaban los SUBSCRIBE, así que
            // alguien dado de baja o bloqueado por un administrador podía seguir
            // mandando mensajes nuevos mientras su conexión siguiera abierta:
            // bastaba con haber entrado al chat antes. El envío no pasa por
            // JwtAuthFilter —no es HTTP— y MensajeService solo mira la pertenencia
            // al chat, no si la cuenta sigue activa. Escribir es peor que leer:
            // deja datos nuevos en nombre de quien ya no debería poder actuar.
            when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(usuario(false, null));

            assertThatThrownBy(() -> interceptor.preSend(envio("/app/chat/42/enviar"), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("bloqueada");
        }

        @Test
        @DisplayName("Tras cerrar sesión tampoco se puede escribir por una conexión abierta")
        void elDeslogueadoNoPuedeEscribir() {
            when(tokenRevocadoService.estaRevocado(JTI)).thenReturn(true);

            assertThatThrownBy(() -> interceptor.preSend(envio("/app/chat/42/enviar"), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("ya no es válida");
        }

        @Test
        @DisplayName("Un SEND sin sesión autenticada se rechaza")
        void elEnvioSinPrincipalSeRechaza() {
            StompHeaderAccessor accesor = StompHeaderAccessor.create(StompCommand.SEND);
            accesor.setDestination("/app/chat/42/enviar");
            accesor.setSessionId("sesion-1");
            accesor.setSessionAttributes(sesionCon(tokenVigente()));

            assertThatThrownBy(() -> interceptor.preSend(
                    MessageBuilder.createMessage(new byte[0], accesor.getMessageHeaders()), canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("sin sesión autenticada");
        }
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private static Message<?> destino(String destino) {
        return subscribe(destino, autenticado(ID_USUARIO));
    }

    private static Message<?> subscribe(String destino, Principal usuario) {
        return subscribe(destino, usuario, sesionCon(tokenVigente()));
    }

    /** Una trama de envío al destino de aplicación, con la sesión en regla. */
    private static Message<?> envio(String destino) {
        StompHeaderAccessor accesor = StompHeaderAccessor.create(StompCommand.SEND);
        accesor.setDestination(destino);
        accesor.setSessionId("sesion-1");
        accesor.setUser(autenticado(ID_USUARIO));
        accesor.setSessionAttributes(sesionCon(tokenVigente()));
        return MessageBuilder.createMessage(new byte[0], accesor.getMessageHeaders());
    }

    private static Message<?> subscribe(String destino, Principal usuario,
                                        Map<String, Object> atributosDeSesion) {
        StompHeaderAccessor accesor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accesor.setDestination(destino);
        accesor.setSessionId("sesion-1");
        accesor.setSubscriptionId("sub-0");
        accesor.setUser(usuario);
        if (atributosDeSesion != null) {
            accesor.setSessionAttributes(atributosDeSesion);
        }
        return MessageBuilder.createMessage(new byte[0], accesor.getMessageHeaders());
    }

    /** Los atributos que JwtHandshakeInterceptor deja en la sesión al aceptarla. */
    private static Map<String, Object> sesionCon(JwtHandshakeInterceptor.DatosToken datos) {
        Map<String, Object> atributos = new HashMap<>();
        atributos.put(JwtHandshakeInterceptor.ATRIBUTO_TOKEN, datos);
        return atributos;
    }

    private static JwtHandshakeInterceptor.DatosToken tokenVigente() {
        return new JwtHandshakeInterceptor.DatosToken(
                JTI, Instant.now().plusSeconds(3600), Instant.now());
    }

    /** El Principal tiene la forma que le da UsuarioHandshakeHandler. */
    private static Principal autenticado(Long id) {
        UsuarioPrincipal usuario = new UsuarioPrincipal(
                id, EMAIL, "$2a$10$hash", Rol.CIUDADANO, true, null);
        return new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities());
    }

    /** Lo que la base de datos responde ahora, que puede no ser lo del handshake. */
    private static UsuarioPrincipal usuario(boolean activo, Instant passwordChangedAt) {
        return new UsuarioPrincipal(
                ID_USUARIO, EMAIL, "$2a$10$hash", Rol.CIUDADANO, activo, passwordChangedAt);
    }
}
