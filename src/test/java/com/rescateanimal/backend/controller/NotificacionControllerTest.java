package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.NotificacionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.ControlIntentosService;
import com.rescateanimal.backend.service.NotificacionService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de /api/notificaciones (docs/api-rest.md §12), con el service
 * simulado: quién ve qué lo prueban NotificacionServiceTest y
 * NotificacionesQueryTest contra PostgreSQL.
 */
@WebMvcTest(NotificacionController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificacionControllerTest {

    private static final Long ID_USUARIO = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificacionService notificacionService;
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

    @Test
    @DisplayName("GET -> 200 con la página de las notificaciones del usuario del token")
    void listarDevuelve200() throws Exception {
        when(notificacionService.listar(eq(ID_USUARIO), isNull(), any())).thenReturn(new PaginaResponse<>(
                List.of(NotificacionResponse.builder().id(1L).tipo(TipoNotificacion.NUEVO_AVISTAMIENTO)
                        .referenciaId(55L).leida(false).createdAt(Instant.now()).build()),
                0, 20, 1, 1, true));

        mockMvc.perform(get("/api/notificaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tipo").value("NUEVO_AVISTAMIENTO"))
                .andExpect(jsonPath("$.content[0].referenciaId").value(55))
                .andExpect(jsonPath("$.content[0].leida").value(false))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET ?leida=false -> el filtro llega al service")
    void filtroLeida() throws Exception {
        when(notificacionService.listar(eq(ID_USUARIO), eq(false), any()))
                .thenReturn(new PaginaResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/notificaciones").param("leida", "false"))
                .andExpect(status().isOk());

        verify(notificacionService).listar(eq(ID_USUARIO), eq(false), any());
    }

    @Test
    @DisplayName("GET ?leida=quizas -> 400, no 500")
    void filtroLeidaInvalido() throws Exception {
        mockMvc.perform(get("/api/notificaciones").param("leida", "quizas"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(notificacionService);
    }

    @Test
    @DisplayName("PATCH /{id}/leida -> 204")
    void marcarLeidaDevuelve204() throws Exception {
        mockMvc.perform(patch("/api/notificaciones/9/leida"))
                .andExpect(status().isNoContent());

        verify(notificacionService).marcarLeida(9L, ID_USUARIO);
    }

    @Test
    @DisplayName("PATCH de una notificación ajena -> 403 ACCESO_DENEGADO")
    void ajenaDevuelve403() throws Exception {
        doThrow(new AccessDeniedException("No es tuya")).when(notificacionService).marcarLeida(9L, ID_USUARIO);

        mockMvc.perform(patch("/api/notificaciones/9/leida"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("PATCH de una notificación inexistente -> 404 NO_ENCONTRADO")
    void inexistenteDevuelve404() throws Exception {
        doThrow(new ResourceNotFoundException("No existe")).when(notificacionService).marcarLeida(9L, ID_USUARIO);

        mockMvc.perform(patch("/api/notificaciones/9/leida"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("PATCH con un id no numérico -> 400, no 500")
    void idNoNumerico() throws Exception {
        mockMvc.perform(patch("/api/notificaciones/abc/leida"))
                .andExpect(status().isBadRequest());
    }
}
