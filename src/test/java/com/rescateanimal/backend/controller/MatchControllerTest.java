package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.LimiteMatchResponse;
import com.rescateanimal.backend.dto.response.MatchResponse;
import com.rescateanimal.backend.exception.LimiteMatchesAlcanzadoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.PublicacionYaDeslizadaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.MatchService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de /api/matches (docs/api-rest.md §6): que cada situación
 * devuelve el código documentado, con especial atención al cuerpo del 429, que
 * debe llevar el límite y la hora de reinicio (RF-24).
 */
@WebMvcTest(MatchController.class)
@AutoConfigureMockMvc(addFilters = false)
class MatchControllerTest {

    private static final Long ID_USUARIO = 7L;

    private static final String MATCH = """
            {"publicacionId":33,"tipoPublicacion":"AVISTAMIENTO","interesado":true}
            """;
    private static final String DESCARTE = """
            {"publicacionId":33,"tipoPublicacion":"AVISTAMIENTO","interesado":false}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchService matchService;
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

    @Test
    @DisplayName("Match correcto -> 201 con el chat abierto")
    void matchDevuelve201ConChat() throws Exception {
        when(matchService.deslizar(any(), eq(ID_USUARIO))).thenReturn(respuesta(true, 200L));

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON).content(MATCH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.interesado").value(true))
                .andExpect(jsonPath("$.chatId").value(200));
    }

    @Test
    @DisplayName("Descarte -> 201 y el chatId ni siquiera aparece en el JSON")
    void descarteDevuelve201SinChat() throws Exception {
        when(matchService.deslizar(any(), eq(ID_USUARIO))).thenReturn(respuesta(false, null));

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON).content(DESCARTE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.interesado").value(false))
                .andExpect(jsonPath("$.chatId").doesNotExist());
    }

    @Test
    @DisplayName("El swipe se registra a nombre del usuario del token")
    void usaElUsuarioDelToken() throws Exception {
        when(matchService.deslizar(any(), any())).thenReturn(respuesta(true, 200L));

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON).content(MATCH))
                .andExpect(status().isCreated());

        verify(matchService).deslizar(any(), eq(ID_USUARIO));
    }

    @Test
    @DisplayName("Sin el campo interesado -> 400: un cuerpo incompleto no puede ser un match implícito")
    void sinInteresadoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"publicacionId":33,"tipoPublicacion":"AVISTAMIENTO"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.interesado").exists());
    }

    @Test
    @DisplayName("Tipo de publicación desconocido -> 400, no 500")
    void tipoDesconocidoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"publicacionId":33,"tipoPublicacion":"LO_QUE_SEA","interesado":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ADOPCION todavía no disponible -> 400 con su propio código")
    void adopcionDevuelve400() throws Exception {
        when(matchService.deslizar(any(), any())).thenThrow(new OperacionNoPermitidaException(
                "TIPO_PUBLICACION_NO_DISPONIBLE", "Por ahora solo se pueden deslizar avistamientos"));

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"publicacionId":33,"tipoPublicacion":"ADOPCION","interesado":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TIPO_PUBLICACION_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("Publicación inexistente -> 404")
    void publicacionInexistenteDevuelve404() throws Exception {
        when(matchService.deslizar(any(), any()))
                .thenThrow(new ResourceNotFoundException("El avistamiento 33 no existe"));

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON).content(MATCH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Segundo swipe sobre la misma publicación -> 409")
    void dobleSwipeDevuelve409() throws Exception {
        when(matchService.deslizar(any(), any()))
                .thenThrow(new PublicacionYaDeslizadaException("Ya habías deslizado esta publicación"));

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON).content(MATCH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SWIPE_DUPLICADO"));
    }

    @Test
    @DisplayName("Cuota diaria agotada -> 429 con el límite y la hora de reinicio (RF-24)")
    void limiteAlcanzadoDevuelve429() throws Exception {
        LocalDateTime manyana = LocalDate.now().plusDays(1).atStartOfDay();
        when(matchService.deslizar(any(), any()))
                .thenThrow(new LimiteMatchesAlcanzadoException(20, manyana));

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON).content(MATCH))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("LIMITE_MATCHES_ALCANZADO"))
                .andExpect(jsonPath("$.mensaje").value(
                        "Has alcanzado el límite de 20 matches diarios. Podrás continuar mañana."))
                .andExpect(jsonPath("$.limiteDiario").value(20))
                .andExpect(jsonPath("$.reiniciaEn").exists());
    }

    @Test
    @DisplayName("GET /mios -> 200 y solo consulta los del usuario autenticado")
    void misMatchesDevuelve200() throws Exception {
        when(matchService.listarMisMatches(ID_USUARIO)).thenReturn(List.of(respuesta(true, 200L)));

        mockMvc.perform(get("/api/matches/mios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].chatId").value(200));

        verify(matchService).listarMisMatches(ID_USUARIO);
    }

    @Test
    @DisplayName("GET /limite -> 200 con usados, máximo y momento de reinicio")
    void limiteDevuelve200() throws Exception {
        when(matchService.consultarLimite(ID_USUARIO)).thenReturn(LimiteMatchResponse.builder()
                .limiteDiario(20).consumidos(3).restantes(17)
                .reiniciaEn(LocalDate.now().plusDays(1).atStartOfDay())
                .build());

        mockMvc.perform(get("/api/matches/limite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limiteDiario").value(20))
                .andExpect(jsonPath("$.consumidos").value(3))
                .andExpect(jsonPath("$.restantes").value(17))
                .andExpect(jsonPath("$.reiniciaEn").exists());
    }

    private static MatchResponse respuesta(boolean interesado, Long chatId) {
        return MatchResponse.builder()
                .id(100L)
                .publicacionId(33L)
                .tipoPublicacion(TipoPublicacion.AVISTAMIENTO)
                .interesado(interesado)
                .chatId(chatId)
                .createdAt(Instant.now())
                .build();
    }
}
