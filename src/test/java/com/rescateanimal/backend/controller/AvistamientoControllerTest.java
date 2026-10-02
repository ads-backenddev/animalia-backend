package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.AvistamientoFeedResponse;
import com.rescateanimal.backend.dto.response.AvistamientoResponse;
import com.rescateanimal.backend.dto.response.UbicacionResponse;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AvistamientoService;
import com.rescateanimal.backend.service.ControlIntentosService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de /api/avistamientos (docs/api-rest.md §5).
 */
@WebMvcTest(AvistamientoController.class)
@AutoConfigureMockMvc(addFilters = false)
class AvistamientoControllerTest {

    private static final Long ID_USUARIO = 7L;

    private static final String CUERPO_VALIDO = """
            {"alertaId":55,"descripcion":"Lo he visto en el parque",
             "fotoUrl":"https://fotos/avistamiento.jpg",
             "ubicacion":{"latitud":40.4168,"longitud":-3.7038}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvistamientoService avistamientoService;
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
    @DisplayName("Avistamiento válido -> 201 con Location")
    void avistamientoValidoDevuelve201() throws Exception {
        when(avistamientoService.registrar(any(), eq(ID_USUARIO))).thenReturn(respuesta());

        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/avistamientos/77"))
                .andExpect(jsonPath("$.id").value(77))
                .andExpect(jsonPath("$.alertaId").value(55))
                .andExpect(jsonPath("$.ubicacion.longitud").value(-3.7038));
    }

    @Test
    @DisplayName("El avistamiento se registra a nombre del usuario del token")
    void usaElUsuarioDelToken() throws Exception {
        when(avistamientoService.registrar(any(), any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isCreated());

        verify(avistamientoService).registrar(any(), eq(ID_USUARIO));
    }

    @Test
    @DisplayName("El autor no expone email ni teléfono (RNF-10)")
    void elAutorNoExponeDatosDeContacto() throws Exception {
        when(avistamientoService.registrar(any(), any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.autor.nombre").value("Ana"))
                .andExpect(jsonPath("$.autor.email").doesNotExist())
                .andExpect(jsonPath("$.autor.telefono").doesNotExist());
    }

    @Test
    @DisplayName("Sin alertaId -> 400 señalando el campo")
    void sinAlertaDevuelve400() throws Exception {
        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"descripcion":"Lo he visto","fotoUrl":"https://f/a.jpg",
                                 "ubicacion":{"latitud":40.4,"longitud":-3.7}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.alertaId").exists());
    }

    @Test
    @DisplayName("Sin foto -> 400: el swipe se decide por la imagen (RF-21)")
    void sinFotoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alertaId":55,"descripcion":"Lo he visto",
                                 "ubicacion":{"latitud":40.4,"longitud":-3.7}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.fotoUrl").exists());
    }

    @Test
    @DisplayName("Foto javascript: -> 400: fotoUrl acaba dentro de un <img src>")
    void fotoNoHttpsDevuelve400() throws Exception {
        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alertaId":55,"descripcion":"Lo he visto","fotoUrl":"javascript:alert(1)",
                                 "ubicacion":{"latitud":40.4,"longitud":-3.7}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.fotoUrl").value("la foto debe ser una URL https:// válida"));
    }

    @Test
    @DisplayName("Latitud fuera de rango -> 400 señalando el campo anidado")
    void latitudFueraDeRangoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alertaId":55,"descripcion":"Lo he visto","fotoUrl":"https://f/a.jpg",
                                 "ubicacion":{"latitud":120.0,"longitud":-3.7}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores['ubicacion.latitud']").exists());
    }

    @Test
    @DisplayName("Alerta inexistente -> 404")
    void alertaInexistenteDevuelve404() throws Exception {
        when(avistamientoService.registrar(any(), any()))
                .thenThrow(new ResourceNotFoundException("La alerta 55 no existe"));

        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("Alerta ya cerrada -> 400 con su propio código, distinguible del 404")
    void alertaCerradaDevuelve400() throws Exception {
        when(avistamientoService.registrar(any(), any())).thenThrow(new OperacionNoPermitidaException(
                "ALERTA_NO_ACTIVA", "La alerta ya no está activa: no admite avistamientos nuevos"));

        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("ALERTA_NO_ACTIVA"));
    }

    @Test
    @DisplayName("GET /feed -> 200 y consulta el feed del usuario autenticado")
    void feedDevuelve200() throws Exception {
        when(avistamientoService.feed(ID_USUARIO)).thenReturn(List.of(
                AvistamientoFeedResponse.builder()
                        .id(77L)
                        .fotoUrl("https://fotos/avistamiento.jpg")
                        .ubicacion(new UbicacionResponse(40.4168, -3.7038))
                        .autor(AutorResponse.builder().id(8L).nombre("Luis").build())
                        .alertaId(55L)
                        .alertaEspecie("perro")
                        .alertaFotoUrl("https://fotos/alerta.jpg")
                        .build()));

        mockMvc.perform(get("/api/avistamientos/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(77))
                .andExpect(jsonPath("$[0].alertaEspecie").value("perro"))
                .andExpect(jsonPath("$[0].alertaFotoUrl").value("https://fotos/alerta.jpg"));

        verify(avistamientoService).feed(ID_USUARIO);
    }

    @Test
    @DisplayName("JSON malformado -> 400, no 500")
    void jsonMalformadoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/avistamientos")
                        .contentType(MediaType.APPLICATION_JSON).content("{esto no es json"))
                .andExpect(status().isBadRequest());
    }

    private static AvistamientoResponse respuesta() {
        return AvistamientoResponse.builder()
                .id(77L)
                .alertaId(55L)
                .descripcion("Lo he visto en el parque")
                .fotoUrl("https://fotos/avistamiento.jpg")
                .ubicacion(new UbicacionResponse(40.4168, -3.7038))
                .createdAt(Instant.now())
                .autor(AutorResponse.builder().id(ID_USUARIO).nombre("Ana").build())
                .build();
    }
}
