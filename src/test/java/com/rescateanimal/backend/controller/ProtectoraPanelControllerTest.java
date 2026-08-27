package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.AutorResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ResumenPanelResponse;
import com.rescateanimal.backend.dto.response.SolicitudAdopcionResponse;
import com.rescateanimal.backend.dto.response.ValoracionesPanelResponse;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.ControlIntentosService;
import com.rescateanimal.backend.service.ProtectoraPanelService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de /api/protectoras/panel (RF-10, docs/api-rest.md §9).
 *
 * Lo que más importa fijar aquí es que el @PreAuthorize de la clase cubre los
 * CUATRO endpoints: está escrito una sola vez, así que una prueba por endpoint
 * es lo que demuestra que efectivamente se aplica a todos y no solo al primero.
 *
 * Que una protectora sin verificar no entre lo decide el service —depende de una
 * fila de la base de datos, no del rol—, y aquí se comprueba que su 403 llega al
 * cliente con el código correcto.
 */
@WebMvcTest(ProtectoraPanelController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProtectoraPanelControllerTest.SeguridadDeMetodo.class)
class ProtectoraPanelControllerTest {

    /**
     * Activa @PreAuthorize dentro de la rebanada, igual que en
     * AnimalAdopcionControllerTest: sin esto un CIUDADANO entraría en el panel y
     * las pruebas de rol no comprobarían nada.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SeguridadDeMetodo {
    }

    private static final Long ID_PROTECTORA = 20L;
    private static final Long ID_CIUDADANO = 7L;

    /** Las cuatro rutas del panel, para las reglas que valen en todas. */
    private static final List<String> RUTAS = List.of(
            "/api/protectoras/panel/animales",
            "/api/protectoras/panel/solicitudes",
            "/api/protectoras/panel/valoraciones",
            "/api/protectoras/panel/resumen");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProtectoraPanelService protectoraPanelService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;
    @MockitoBean
    private TokenRevocadoService tokenRevocadoService;
    @MockitoBean
    private ControlIntentosService controlIntentosService;

    @AfterEach
    void limpiarContexto() {
        TestSecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Permisos")
    class Permisos {

        @Test
        @DisplayName("Un CIUDADANO recibe 403 en los cuatro endpoints del panel")
        void elCiudadanoNoEntraEnNinguno() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);

            for (String ruta : RUTAS) {
                mockMvc.perform(get(ruta)).andExpect(status().isForbidden());
            }

            // El rol se rechaza antes de llegar al service: ni se consulta.
            verifyNoInteractions(protectoraPanelService);
        }

        @Test
        @DisplayName("Un ADMIN tampoco: el panel es de la protectora, no de quien manda")
        void elAdminTampocoEntra() throws Exception {
            autenticar(99L, Rol.ADMIN);

            for (String ruta : RUTAS) {
                mockMvc.perform(get(ruta)).andExpect(status().isForbidden());
            }
            verifyNoInteractions(protectoraPanelService);
        }

        @Test
        @DisplayName("Una protectora PENDIENTE recibe 403 con PROTECTORA_PENDIENTE")
        void laPendienteRecibe403ConCodigo() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(protectoraPanelService.resumen(ID_PROTECTORA))
                    .thenThrow(new CuentaNoAutorizadaException("PROTECTORA_PENDIENTE",
                            "Tu protectora está pendiente de verificación por un administrador"));

            mockMvc.perform(get("/api/protectoras/panel/resumen"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("PROTECTORA_PENDIENTE"))
                    .andExpect(jsonPath("$.mensaje")
                            .value("Tu protectora está pendiente de verificación por un administrador"));
        }

        @Test
        @DisplayName("Una protectora RECHAZADA recibe 403 con PROTECTORA_RECHAZADA")
        void laRechazadaRecibe403ConCodigo() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(protectoraPanelService.solicitudes(ID_PROTECTORA))
                    .thenThrow(new CuentaNoAutorizadaException("PROTECTORA_RECHAZADA",
                            "La solicitud de tu protectora ha sido rechazada"));

            mockMvc.perform(get("/api/protectoras/panel/solicitudes"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("PROTECTORA_RECHAZADA"));
        }

        @Test
        @DisplayName("El id sale siempre del token: ninguna ruta admite uno del cliente")
        void elIdSaleDelToken() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(protectoraPanelService.resumen(any())).thenReturn(resumen());

            mockMvc.perform(get("/api/protectoras/panel/resumen").param("protectoraId", "999"))
                    .andExpect(status().isOk());

            // El parámetro sobra y se ignora: el service recibe el del token.
            org.mockito.Mockito.verify(protectoraPanelService).resumen(eq(ID_PROTECTORA));
        }
    }

    @Nested
    @DisplayName("Respuestas")
    class Respuestas {

        @Test
        @DisplayName("GET /animales -> 200 con la página de animales en cualquier estado")
        void animalesDevuelve200() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(protectoraPanelService.animales(eq(ID_PROTECTORA), any()))
                    .thenReturn(paginaConRetirado());

            mockMvc.perform(get("/api/protectoras/panel/animales"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].nombre").value("Tina"))
                    .andExpect(jsonPath("$.content[0].estado").value("RETIRADO"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("GET /solicitudes -> 200 y el interesado viaja sin email ni teléfono")
        void solicitudesNoExponeContacto() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(protectoraPanelService.solicitudes(ID_PROTECTORA)).thenReturn(List.of(solicitud()));

            mockMvc.perform(get("/api/protectoras/panel/solicitudes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].interesado.nombre").value("Ana Ciudadana"))
                    .andExpect(jsonPath("$[0].interesado.email").doesNotExist())
                    .andExpect(jsonPath("$[0].interesado.telefono").doesNotExist())
                    .andExpect(jsonPath("$[0].chatId").value(900))
                    .andExpect(jsonPath("$[0].animalEstado").value("DISPONIBLE"));
        }

        @Test
        @DisplayName("GET /valoraciones -> 200 con lista vacía y sin media mientras no haya bloque 9")
        void valoracionesDevuelveVacio() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(protectoraPanelService.valoraciones(ID_PROTECTORA)).thenReturn(
                    ValoracionesPanelResponse.builder()
                            .valoraciones(List.of()).media(null).total(0).build());

            mockMvc.perform(get("/api/protectoras/panel/valoraciones"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valoraciones").isEmpty())
                    .andExpect(jsonPath("$.total").value(0))
                    // Ausente, no cero: un cero se leería como la peor nota.
                    .andExpect(jsonPath("$.media").doesNotExist());
        }

        @Test
        @DisplayName("GET /resumen -> 200 con todos los contadores")
        void resumenDevuelve200() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(protectoraPanelService.resumen(ID_PROTECTORA)).thenReturn(resumen());

            mockMvc.perform(get("/api/protectoras/panel/resumen"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.animalesDisponibles").value(4))
                    .andExpect(jsonPath("$.animalesAdoptados").value(3))
                    .andExpect(jsonPath("$.animalesRetirados").value(2))
                    .andExpect(jsonPath("$.animalesTotales").value(9))
                    .andExpect(jsonPath("$.solicitudesPendientes").value(5))
                    .andExpect(jsonPath("$.solicitudesTotales").value(11))
                    .andExpect(jsonPath("$.valoracionMedia").value(4.5))
                    .andExpect(jsonPath("$.totalValoraciones").value(6));
        }
    }

    // ---------------------------------------------------------------------

    private static void autenticar(Long id, Rol rol) {
        UsuarioPrincipal principal = new UsuarioPrincipal(
                id, "cuenta" + id + "@test.es", "$2a$10$hash", rol, true, null);
        TestSecurityContextHolder.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static ResumenPanelResponse resumen() {
        return ResumenPanelResponse.builder()
                .animalesDisponibles(4)
                .animalesAdoptados(3)
                .animalesRetirados(2)
                .animalesTotales(9)
                .solicitudesPendientes(5)
                .solicitudesTotales(11)
                .valoracionMedia(new BigDecimal("4.50"))
                .totalValoraciones(6)
                .build();
    }

    private static SolicitudAdopcionResponse solicitud() {
        return SolicitudAdopcionResponse.builder()
                .id(500L)
                .interesado(AutorResponse.builder()
                        .id(77L).nombre("Ana Ciudadana").fotoUrl("https://cdn.test/ana.jpg").build())
                .animalId(33L)
                .animalNombre("Luna")
                .animalEstado("DISPONIBLE")
                .chatId(900L)
                .createdAt(Instant.parse("2026-08-20T10:00:00Z"))
                .build();
    }

    /** Una página con un RETIRADO: lo que el panel enseña y el catálogo público no. */
    private static PaginaResponse<AnimalAdopcionResponse> paginaConRetirado() {
        return PaginaResponse.<AnimalAdopcionResponse>builder()
                .content(List.of(AnimalAdopcionResponse.builder()
                        .id(33L)
                        .nombre("Tina")
                        .especie("Perro")
                        .estado("RETIRADO")
                        .protectoraId(ID_PROTECTORA)
                        .protectoraNombre("Protectora de prueba")
                        .createdAt(Instant.parse("2026-08-20T10:00:00Z"))
                        .build()))
                .page(0).size(20).totalElements(1).totalPages(1).last(true)
                .build();
    }
}
