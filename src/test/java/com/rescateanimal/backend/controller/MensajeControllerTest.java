package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.EnviarMensajeRequest;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.exception.ChatAjenoException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.MensajeService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de la escritura en /api/chats (docs/api-rest.md §7): el envío
 * por la vía REST y el marcado de leídos.
 *
 * Va aparte de ChatControllerTest porque prueba otro controller, aunque
 * compartan prefijo de ruta. Lo que aquí importa fijar es el 201 sin Location
 * —un mensaje no tiene URL propia—, que el verbo de leídos es PUT y no PATCH, y
 * que ni el emisor ni el chat se pueden inyectar por el cuerpo: salen del token
 * y de la ruta.
 *
 * Que el mensaje se difunda por /topic/chat/{id} después de confirmar la
 * transacción es de MensajeService y se verifica en MensajeServiceTest; desde
 * HTTP solo se ve el 201.
 */
@WebMvcTest(MensajeController.class)
@AutoConfigureMockMvc(addFilters = false)
class MensajeControllerTest {

    private static final Long ID_USUARIO = 7L;
    private static final Long ID_CHAT = 42L;

    private static final String MENSAJE = """
            {"contenido":"¿Sigue por la zona?"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MensajeService mensajeService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;
    @MockitoBean
    private TokenRevocadoService tokenRevocadoService;

    @BeforeEach
    void autenticarUsuario() {
        UsuarioPrincipal principal = new UsuarioPrincipal(
                ID_USUARIO, "ana@test.es", "$2a$10$hash", Rol.CIUDADANO, true, null);
        TestSecurityContextHolder.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void limpiarContexto() {
        TestSecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------
    // POST /api/chats/{id}/mensajes
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Mensaje correcto -> 201 con el mensaje ya guardado")
    void enviarDevuelve201() throws Exception {
        when(mensajeService.enviar(eq(ID_CHAT), eq(ID_USUARIO), any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON).content(MENSAJE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(500))
                .andExpect(jsonPath("$.chatId").value(ID_CHAT))
                .andExpect(jsonPath("$.contenido").value("¿Sigue por la zona?"))
                .andExpect(jsonPath("$.leido").value(false))
                .andExpect(jsonPath("$.emisor.id").value(ID_USUARIO));
    }

    @Test
    @DisplayName("El 201 no lleva Location: un mensaje se lee dentro del histórico de su chat")
    void enviarNoDevuelveLocation() throws Exception {
        when(mensajeService.enviar(any(), any(), any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON).content(MENSAJE))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("El emisor sale del token y el chat de la ruta, nunca del cuerpo")
    void elEmisorYElChatNoSalenDelCuerpo() throws Exception {
        when(mensajeService.enviar(any(), any(), any())).thenReturn(respuesta());

        // Un cliente malicioso intenta colar otro emisor y otro chat en el JSON.
        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contenido":"hola","emisorId":999,"chatId":123}
                                """))
                .andExpect(status().isCreated());

        // Se ignoran: el service recibe el chat de la ruta y el usuario del token.
        verify(mensajeService).enviar(eq(ID_CHAT), eq(ID_USUARIO), any());
    }

    @Test
    @DisplayName("El contenido llega al service tal cual lo mandó el cliente")
    void enviarPropagaElContenido() throws Exception {
        when(mensajeService.enviar(any(), any(), any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON).content(MENSAJE))
                .andExpect(status().isCreated());

        ArgumentCaptor<EnviarMensajeRequest> captor = ArgumentCaptor.forClass(EnviarMensajeRequest.class);
        verify(mensajeService).enviar(any(), any(), captor.capture());
        assertThat(captor.getValue().getContenido()).isEqualTo("¿Sigue por la zona?");
    }

    @Test
    @DisplayName("Mensaje vacío -> 400 con el campo señalado, y no llega al service")
    void contenidoVacioDevuelve400() throws Exception {
        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contenido":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.contenido").exists());

        verify(mensajeService, never()).enviar(any(), any(), any());
    }

    @Test
    @DisplayName("Solo espacios -> 400: @NotBlank, no @NotNull")
    void contenidoEnBlancoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contenido":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.contenido").exists());
    }

    @Test
    @DisplayName("Sin el campo contenido -> 400")
    void sinContenidoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.contenido").exists());
    }

    @Test
    @DisplayName("Más de 2000 caracteres -> 400: el tope acota lo que se difunde por el WebSocket")
    void contenidoDemasiadoLargoDevuelve400() throws Exception {
        String largo = "a".repeat(2001);

        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contenido\":\"" + largo + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.contenido").exists());

        verify(mensajeService, never()).enviar(any(), any(), any());
    }

    @Test
    @DisplayName("Exactamente 2000 caracteres -> 201: el límite es inclusivo")
    void contenidoEnElLimiteSeAcepta() throws Exception {
        when(mensajeService.enviar(any(), any(), any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contenido\":\"" + "a".repeat(2000) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Cuerpo ilegible -> 400, no 500")
    void cuerpoIlegibleDevuelve400() throws Exception {
        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contenido\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Escribir en un chat ajeno -> 403 CHAT_AJENO")
    void enviarEnChatAjenoDevuelve403() throws Exception {
        when(mensajeService.enviar(any(), any(), any()))
                .thenThrow(new ChatAjenoException("No participas en esta conversación"));

        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON).content(MENSAJE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CHAT_AJENO"));
    }

    @Test
    @DisplayName("Escribir en un chat inexistente -> 404")
    void enviarEnChatInexistenteDevuelve404() throws Exception {
        when(mensajeService.enviar(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("El chat 42 no existe"));

        mockMvc.perform(post("/api/chats/{id}/mensajes", ID_CHAT)
                        .contentType(MediaType.APPLICATION_JSON).content(MENSAJE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    // ---------------------------------------------------------------------
    // PUT /api/chats/{id}/mensajes/leidos
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("PUT /leidos -> 204 sin cuerpo")
    void marcarLeidosDevuelve204() throws Exception {
        mockMvc.perform(put("/api/chats/{id}/mensajes/leidos", ID_CHAT))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist());

        verify(mensajeService).marcarLeidos(ID_CHAT, ID_USUARIO);
    }

    @Test
    @DisplayName("Repetir el marcado -> 204 otra vez: es idempotente, por eso es PUT")
    void marcarLeidosEsIdempotente() throws Exception {
        when(mensajeService.marcarLeidos(ID_CHAT, ID_USUARIO)).thenReturn(3, 0);

        mockMvc.perform(put("/api/chats/{id}/mensajes/leidos", ID_CHAT))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/chats/{id}/mensajes/leidos", ID_CHAT))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /leidos -> 405: el verbo documentado es PUT")
    void marcarLeidosNoAceptaPatch() throws Exception {
        mockMvc.perform(patch("/api/chats/{id}/mensajes/leidos", ID_CHAT))
                .andExpect(status().isMethodNotAllowed());

        verify(mensajeService, never()).marcarLeidos(any(), any());
    }

    @Test
    @DisplayName("Marcar leídos en un chat ajeno -> 403")
    void marcarLeidosEnChatAjenoDevuelve403() throws Exception {
        when(mensajeService.marcarLeidos(any(), any()))
                .thenThrow(new ChatAjenoException("No participas en esta conversación"));

        mockMvc.perform(put("/api/chats/{id}/mensajes/leidos", ID_CHAT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CHAT_AJENO"));
    }

    @Test
    @DisplayName("Marcar leídos en un chat inexistente -> 404")
    void marcarLeidosEnChatInexistenteDevuelve404() throws Exception {
        when(mensajeService.marcarLeidos(any(), any()))
                .thenThrow(new ResourceNotFoundException("El chat 42 no existe"));

        mockMvc.perform(put("/api/chats/{id}/mensajes/leidos", ID_CHAT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private static MensajeResponse respuesta() {
        return MensajeResponse.builder()
                .id(500L)
                .chatId(ID_CHAT)
                .emisor(AutorResponse.builder()
                        .id(ID_USUARIO).nombre("Ana").fotoUrl("https://cdn.test/ana.jpg").build())
                .contenido("¿Sigue por la zona?")
                .leido(false)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
