package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.ChatDetalleResponse;
import com.rescateanimal.backend.dto.response.ChatResumenResponse;
import com.rescateanimal.backend.dto.response.MensajeResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.ChatAjenoException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.ChatService;
import com.rescateanimal.backend.service.ControlIntentosService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de las consultas de /api/chats (docs/api-rest.md §7).
 *
 * Como en los demás controllers, se prueba junto a GlobalExceptionHandler con
 * el service simulado: quién participa en un chat, cómo se ordena el histórico
 * y de dónde salen los no leídos se verifica en ChatServiceTest.
 *
 * Lo específico de este recurso es que el permiso no es de rol sino de
 * participación, así que no hay @PreAuthorize que probar; lo que sí tiene que
 * quedar fijado aquí es que el 403 del chat ajeno y el 404 del inexistente
 * llegan al cliente como códigos distintos, y que el id del usuario que se
 * consulta sale siempre del token y nunca de la petición.
 */
@WebMvcTest(ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatControllerTest {

    private static final Long ID_USUARIO = 7L;
    private static final Long ID_CHAT = 42L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;
    @MockitoBean
    private TokenRevocadoService tokenRevocadoService;
    @MockitoBean
    private ControlIntentosService controlIntentosService;

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
    // GET /api/chats
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/chats -> 200 con interlocutor, último mensaje y no leídos")
    void listarDevuelve200() throws Exception {
        when(chatService.listarMisChats(ID_USUARIO)).thenReturn(List.of(resumen(3L)));

        mockMvc.perform(get("/api/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID_CHAT))
                .andExpect(jsonPath("$[0].otroParticipante.id").value(9))
                .andExpect(jsonPath("$[0].otroParticipante.nombre").value("Luis"))
                .andExpect(jsonPath("$[0].ultimoMensaje.contenido").value("¿Sigue por la zona?"))
                .andExpect(jsonPath("$[0].noLeidos").value(3));
    }

    @Test
    @DisplayName("El interlocutor no lleva email ni teléfono (RNF-10)")
    void elInterlocutorNoExponeDatosPersonales() throws Exception {
        when(chatService.listarMisChats(ID_USUARIO)).thenReturn(List.of(resumen(0L)));

        mockMvc.perform(get("/api/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].otroParticipante.email").doesNotExist())
                .andExpect(jsonPath("$[0].otroParticipante.telefono").doesNotExist());
    }

    @Test
    @DisplayName("Conversación sin mensajes -> ultimoMensaje se omite del JSON, no viaja como null")
    void chatSinMensajesOmiteElUltimo() throws Exception {
        ChatResumenResponse vacio = resumen(0L);
        vacio.setUltimoMensaje(null);
        when(chatService.listarMisChats(ID_USUARIO)).thenReturn(List.of(vacio));

        mockMvc.perform(get("/api/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ultimoMensaje").doesNotExist())
                .andExpect(jsonPath("$[0].noLeidos").value(0));
    }

    @Test
    @DisplayName("Sin conversaciones -> 200 con lista vacía, no 404")
    void sinChatsDevuelveListaVacia() throws Exception {
        when(chatService.listarMisChats(ID_USUARIO)).thenReturn(List.of());

        mockMvc.perform(get("/api/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("La lista se pide siempre para el usuario del token")
    void listarUsaElUsuarioDelToken() throws Exception {
        when(chatService.listarMisChats(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/chats")).andExpect(status().isOk());

        verify(chatService).listarMisChats(ID_USUARIO);
    }

    // ---------------------------------------------------------------------
    // GET /api/chats/{id}
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/chats/{id} -> 200 con el contexto de la publicación que originó el chat")
    void detalleDevuelve200() throws Exception {
        when(chatService.detalle(ID_CHAT, ID_USUARIO)).thenReturn(detalle());

        mockMvc.perform(get("/api/chats/{id}", ID_CHAT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_CHAT))
                .andExpect(jsonPath("$.otroParticipante.nombre").value("Luis"))
                .andExpect(jsonPath("$.matchId").value(55))
                .andExpect(jsonPath("$.avistamientoId").value(33))
                .andExpect(jsonPath("$.alertaId").value(21))
                .andExpect(jsonPath("$.alertaEstado").value("ACTIVA"));
    }

    @Test
    @DisplayName("Chat de otros -> 403 CHAT_AJENO, no 404: existe, lo que falla es el permiso")
    void chatAjenoDevuelve403() throws Exception {
        when(chatService.detalle(any(), any()))
                .thenThrow(new ChatAjenoException("No participas en esta conversación"));

        mockMvc.perform(get("/api/chats/{id}", ID_CHAT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CHAT_AJENO"))
                .andExpect(jsonPath("$.mensaje").value("No participas en esta conversación"));
    }

    @Test
    @DisplayName("Chat inexistente -> 404, distinguible del 403 anterior")
    void chatInexistenteDevuelve404() throws Exception {
        when(chatService.detalle(any(), any()))
                .thenThrow(new ResourceNotFoundException("El chat 42 no existe"));

        mockMvc.perform(get("/api/chats/{id}", ID_CHAT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Id no numérico -> 400, no 500")
    void idNoNumericoDevuelve400() throws Exception {
        mockMvc.perform(get("/api/chats/{id}", "cuarenta-y-dos"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------
    // GET /api/chats/{id}/mensajes
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GET /{id}/mensajes -> 200 con la página en la forma documentada en §1")
    void historicoDevuelve200() throws Exception {
        when(chatService.historico(eq(ID_CHAT), eq(ID_USUARIO), any())).thenReturn(pagina(0, 20, 45, 3, false));

        mockMvc.perform(get("/api/chats/{id}/mensajes", ID_CHAT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(500))
                .andExpect(jsonPath("$.content[0].chatId").value(ID_CHAT))
                .andExpect(jsonPath("$.content[0].contenido").value("¿Sigue por la zona?"))
                .andExpect(jsonPath("$.content[0].leido").value(false))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(45))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    @DisplayName("La página no expone pageable ni sort: el cuerpo es el DTO, no el Page de Spring Data")
    void historicoNoFiltraDetallesDeSpringData() throws Exception {
        when(chatService.historico(any(), any(), any())).thenReturn(pagina(0, 20, 1, 1, true));

        mockMvc.perform(get("/api/chats/{id}/mensajes", ID_CHAT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist())
                .andExpect(jsonPath("$.numberOfElements").doesNotExist());
    }

    @Test
    @DisplayName("Sin parámetros -> tamaño de página 20 por defecto")
    void historicoAplicaElTamanyoPorDefecto() throws Exception {
        when(chatService.historico(any(), any(), any())).thenReturn(pagina(0, 20, 0, 0, true));

        mockMvc.perform(get("/api/chats/{id}/mensajes", ID_CHAT)).andExpect(status().isOk());

        Pageable recibida = paginacionRecibida();
        assertThat(recibida.getPageSize()).isEqualTo(20);
        assertThat(recibida.getPageNumber()).isZero();
    }

    @Test
    @DisplayName("page y size llegan al service tal cual; acotarlos es cosa suya")
    void historicoPropagaPageYSize() throws Exception {
        when(chatService.historico(any(), any(), any())).thenReturn(pagina(2, 50, 200, 4, false));

        mockMvc.perform(get("/api/chats/{id}/mensajes", ID_CHAT).param("page", "2").param("size", "50"))
                .andExpect(status().isOk());

        Pageable recibida = paginacionRecibida();
        assertThat(recibida.getPageNumber()).isEqualTo(2);
        assertThat(recibida.getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("Histórico de un chat ajeno -> 403, igual que el detalle")
    void historicoDeChatAjenoDevuelve403() throws Exception {
        when(chatService.historico(any(), any(), any()))
                .thenThrow(new ChatAjenoException("No participas en esta conversación"));

        mockMvc.perform(get("/api/chats/{id}/mensajes", ID_CHAT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CHAT_AJENO"));
    }

    @Test
    @DisplayName("Histórico de un chat inexistente -> 404")
    void historicoDeChatInexistenteDevuelve404() throws Exception {
        when(chatService.historico(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("El chat 42 no existe"));

        mockMvc.perform(get("/api/chats/{id}/mensajes", ID_CHAT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /** La paginación con la que el controller acabó llamando al service. */
    private Pageable paginacionRecibida() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(chatService).historico(any(), any(), captor.capture());
        return captor.getValue();
    }

    private static ChatResumenResponse resumen(long noLeidos) {
        return ChatResumenResponse.builder()
                .id(ID_CHAT)
                .otroParticipante(otroParticipante())
                .ultimoMensaje(mensaje())
                .noLeidos(noLeidos)
                .createdAt(Instant.now())
                .build();
    }

    private static ChatDetalleResponse detalle() {
        return ChatDetalleResponse.builder()
                .id(ID_CHAT)
                .otroParticipante(otroParticipante())
                .matchId(55L)
                .avistamientoId(33L)
                .avistamientoFotoUrl("https://cdn.test/avistamiento.jpg")
                .avistamientoDescripcion("Perro marrón cerca del parque")
                .alertaId(21L)
                .alertaEspecie("PERRO")
                .alertaFotoUrl("https://cdn.test/alerta.jpg")
                .alertaEstado("ACTIVA")
                .createdAt(Instant.now())
                .build();
    }

    private static PaginaResponse<MensajeResponse> pagina(
            int page, int size, long total, int totalPages, boolean last) {

        return PaginaResponse.<MensajeResponse>builder()
                .content(List.of(mensaje()))
                .page(page)
                .size(size)
                .totalElements(total)
                .totalPages(totalPages)
                .last(last)
                .build();
    }

    private static MensajeResponse mensaje() {
        return MensajeResponse.builder()
                .id(500L)
                .chatId(ID_CHAT)
                .emisor(otroParticipante())
                .contenido("¿Sigue por la zona?")
                .leido(false)
                .createdAt(Instant.now())
                .build();
    }

    private static AutorResponse otroParticipante() {
        return AutorResponse.builder().id(9L).nombre("Luis").fotoUrl("https://cdn.test/luis.jpg").build();
    }
}
