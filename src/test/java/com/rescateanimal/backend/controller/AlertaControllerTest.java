package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.AlertaDuplicadaResponse;
import com.rescateanimal.backend.dto.response.AlertaMapaResponse;
import com.rescateanimal.backend.dto.response.AlertaResponse;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.UbicacionResponse;
import com.rescateanimal.backend.exception.AlertaDuplicadaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AlertaService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas del contrato HTTP de /api/alertas: que cada situación devuelve el
 * código documentado en docs/api-rest.md §3.
 *
 * Igual que en AuthControllerTest, se prueba el controller junto a
 * GlobalExceptionHandler con el service simulado: la lógica de negocio (radio,
 * ventana temporal, geometría) se verifica en AlertaServiceTest.
 *
 * Los filtros de seguridad se desactivan porque las reglas de acceso son de
 * SecurityConfig; el usuario autenticado se pone directamente en el contexto
 * para que @AuthenticationPrincipal lo resuelva.
 */
@WebMvcTest(AlertaController.class)
@AutoConfigureMockMvc(addFilters = false)
class AlertaControllerTest {

    private static final Long ID_USUARIO = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertaService alertaService;
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

    @Nested
    @DisplayName("POST /api/alertas")
    class Publicacion {

        @Test
        @DisplayName("Alerta válida -> 201 con Location y el recurso creado")
        void alertaValidaDevuelve201() throws Exception {
            when(alertaService.publicar(any(), eq(ID_USUARIO))).thenReturn(alertaResponse());

            mockMvc.perform(post("/api/alertas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpoValido()))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "http://localhost/api/alertas/42"))
                    .andExpect(jsonPath("$.id").value(42))
                    .andExpect(jsonPath("$.especie").value("perro"))
                    .andExpect(jsonPath("$.estado").value("ACTIVA"))
                    .andExpect(jsonPath("$.ubicacion.latitud").value(40.4168))
                    .andExpect(jsonPath("$.ubicacion.longitud").value(-3.7038));
        }

        @Test
        @DisplayName("La alerta se publica a nombre del usuario autenticado, no del que venga en el body")
        void usaElUsuarioDelToken() throws Exception {
            when(alertaService.publicar(any(), any())).thenReturn(alertaResponse());

            mockMvc.perform(post("/api/alertas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpoValido()))
                    .andExpect(status().isCreated());

            verify(alertaService).publicar(any(), eq(ID_USUARIO));
        }

        @Test
        @DisplayName("La respuesta del autor no expone email ni teléfono (RNF-10)")
        void elAutorNoExponeDatosDeContacto() throws Exception {
            when(alertaService.publicar(any(), any())).thenReturn(alertaResponse());

            mockMvc.perform(post("/api/alertas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpoValido()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.autor.nombre").value("Ana"))
                    .andExpect(jsonPath("$.autor.email").doesNotExist())
                    .andExpect(jsonPath("$.autor.telefono").doesNotExist());
        }

        @Test
        @DisplayName("Sin especie -> 400 señalando el campo")
        void sinEspecieDevuelve400() throws Exception {
            mockMvc.perform(post("/api/alertas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo(null, LocalDateTime.now().minusHours(2), "40.4168", "-3.7038")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                    .andExpect(jsonPath("$.errores.especie").exists());
        }

        @Test
        @DisplayName("Latitud fuera de rango -> 400 señalando el campo anidado")
        void latitudFueraDeRangoDevuelve400() throws Exception {
            mockMvc.perform(post("/api/alertas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo("perro", LocalDateTime.now().minusHours(2), "120.0", "-3.7038")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                    .andExpect(jsonPath("$.errores['ubicacion.latitud']").exists());
        }

        @Test
        @DisplayName("Fecha de avistamiento futura -> 400")
        void fechaFuturaDevuelve400() throws Exception {
            mockMvc.perform(post("/api/alertas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo("perro", LocalDateTime.now().plusDays(1), "40.4168", "-3.7038")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores.fechaAvistamiento").exists());
        }

        @Test
        @DisplayName("Alerta duplicada -> 409 con la alerta existente en el cuerpo (RF-25)")
        void alertaDuplicadaDevuelve409ConLaExistente() throws Exception {
            when(alertaService.publicar(any(), any())).thenThrow(new AlertaDuplicadaException(
                    "Ya hay una alerta activa de perro a 120 m publicada en las últimas 24 horas",
                    AlertaDuplicadaResponse.builder()
                            .id(11L).especie("perro").distanciaMetros(120L).build()));

            mockMvc.perform(post("/api/alertas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpoValido()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("ALERTA_DUPLICADA"))
                    .andExpect(jsonPath("$.alertaExistente.id").value(11))
                    .andExpect(jsonPath("$.alertaExistente.especie").value("perro"))
                    .andExpect(jsonPath("$.alertaExistente.distanciaMetros").value(120));
        }

        @Test
        @DisplayName("JSON malformado -> 400, no 500")
        void jsonMalformadoDevuelve400() throws Exception {
            mockMvc.perform(post("/api/alertas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{esto no es json"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Listados")
    class Listados {

        @Test
        @DisplayName("GET /mapa -> 200 con los marcadores")
        void mapaDevuelve200() throws Exception {
            when(alertaService.listarParaMapa()).thenReturn(List.of(AlertaMapaResponse.builder()
                    .id(42L)
                    .especie("perro")
                    .ubicacion(new UbicacionResponse(40.4168, -3.7038))
                    .fechaAvistamiento(LocalDateTime.now().minusHours(2))
                    .estado(EstadoAlerta.ACTIVA)
                    .build()));

            mockMvc.perform(get("/api/alertas/mapa"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(42))
                    .andExpect(jsonPath("$[0].ubicacion.longitud").value(-3.7038));
        }

        @Test
        @DisplayName("GET /mis-alertas -> 200 y consulta solo las del usuario autenticado")
        void misAlertasDevuelve200DelUsuarioAutenticado() throws Exception {
            when(alertaService.listarMisAlertas(ID_USUARIO)).thenReturn(List.of(alertaResponse()));

            mockMvc.perform(get("/api/alertas/mis-alertas"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(42));

            verify(alertaService).listarMisAlertas(ID_USUARIO);
        }
    }

    @Nested
    @DisplayName("PUT /api/alertas/{id}/cerrar")
    class Cierre {

        @Test
        @DisplayName("Cierre por su autor -> 200 con la alerta ya ENCONTRADA")
        void cierreDevuelve200() throws Exception {
            AlertaResponse cerrada = alertaResponse();
            cerrada.setEstado(EstadoAlerta.ENCONTRADA);
            when(alertaService.cerrar(42L, ID_USUARIO)).thenReturn(cerrada);

            mockMvc.perform(put("/api/alertas/42/cerrar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.estado").value("ENCONTRADA"));
        }

        @Test
        @DisplayName("Cerrar la alerta de otro -> 403")
        void cerrarAlertaAjenaDevuelve403() throws Exception {
            when(alertaService.cerrar(42L, ID_USUARIO))
                    .thenThrow(new AccessDeniedException("No es tu alerta"));

            mockMvc.perform(put("/api/alertas/42/cerrar"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
        }

        @Test
        @DisplayName("Cerrar una alerta inexistente -> 404")
        void cerrarAlertaInexistenteDevuelve404() throws Exception {
            when(alertaService.cerrar(999L, ID_USUARIO))
                    .thenThrow(new ResourceNotFoundException("La alerta 999 no existe"));

            mockMvc.perform(put("/api/alertas/999/cerrar"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        }

        @Test
        @DisplayName("Identificador no numérico -> 400, no 500")
        void idNoNumericoDevuelve400() throws Exception {
            mockMvc.perform(put("/api/alertas/abc/cerrar"))
                    .andExpect(status().isBadRequest());
        }
    }

    private static String cuerpoValido() {
        return cuerpo("perro", LocalDateTime.now().minusHours(2), "40.4168", "-3.7038");
    }

    /**
     * La fecha se calcula respecto al momento de la prueba, no se fija literal:
     * @PastOrPresent haría fallar la prueba con el paso del tiempo.
     */
    private static String cuerpo(String especie, LocalDateTime fecha, String latitud, String longitud) {
        return """
                {
                  "descripcion": "Se escapó por la puerta del parque",
                  "especie": %s,
                  "fechaAvistamiento": "%s",
                  "ubicacion": {"latitud": %s, "longitud": %s},
                  "radioBusqueda": 1000
                }
                """.formatted(
                especie == null ? "null" : "\"" + especie + "\"",
                fecha.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                latitud, longitud);
    }

    private static AlertaResponse alertaResponse() {
        return AlertaResponse.builder()
                .id(42L)
                .descripcion("Se escapó por la puerta del parque")
                .especie("perro")
                .fechaAvistamiento(LocalDateTime.now().minusHours(2))
                .ubicacion(new UbicacionResponse(40.4168, -3.7038))
                .radioBusqueda(1000)
                .estado(EstadoAlerta.ACTIVA)
                .createdAt(LocalDateTime.now())
                .autor(AutorResponse.builder().id(ID_USUARIO).nombre("Ana").build())
                .build();
    }
}
