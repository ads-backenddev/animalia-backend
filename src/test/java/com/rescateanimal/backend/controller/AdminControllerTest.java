package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ProtectoraAdminResponse;
import com.rescateanimal.backend.dto.response.UsuarioAdminResponse;
import com.rescateanimal.backend.exception.AutobloqueoException;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AdminService;
import com.rescateanimal.backend.service.ControlIntentosService;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de /api/admin (RF-11, docs/api-rest.md §11).
 *
 * LO QUE ESTE FICHERO EXISTE PARA DEMOSTRAR es que el @PreAuthorize escrito una
 * sola vez en la clase cubre los OCHO endpoints. Está escrito una vez, así que
 * hace falta recorrerlos todos para saber que se aplica a todos y no solo al
 * primero; y son los nueve endpoints más peligrosos de la API. La lista RUTAS es
 * la del controller entera, y cada prueba de permisos la recorre completa.
 *
 * Se prueban los tres roles que existen: CIUDADANO, PROTECTORA y ADMIN. Un
 * denylist ("todos menos ADMIN") no distinguiría entre "se comprueba el rol" y
 * "se comprueba que no seas ciudadano"; recorrer los tres sí.
 */
@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdminControllerTest.SeguridadDeMetodo.class)
class AdminControllerTest {

    /**
     * Activa @PreAuthorize dentro de la rebanada. Sin esto, la anotación de la
     * clase no se aplicaría y TODAS las pruebas de rol de este fichero pasarían
     * sin comprobar absolutamente nada.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SeguridadDeMetodo {
    }

    private static final Long ID_ADMIN = 1L;
    private static final Long ID_OBJETIVO = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminService adminService;
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

    /** Los nueve endpoints del panel, con el verbo y el cuerpo que cada uno pide. */
    private static List<RequestBuilder> rutas() {
        return List.of(
                get("/api/admin/usuarios"),
                put("/api/admin/usuarios/" + ID_OBJETIVO + "/bloquear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Insultos reiterados\"}"),
                put("/api/admin/usuarios/" + ID_OBJETIVO + "/desbloquear"),
                get("/api/admin/protectoras/pendientes"),
                put("/api/admin/protectoras/20/aprobar"),
                put("/api/admin/protectoras/20/rechazar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Documentación insuficiente\"}"),
                get("/api/admin/reportes"),
                put("/api/admin/reportes/5/resolver"),
                delete("/api/admin/publicaciones/33").param("tipo", "ALERTA"));
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Permisos")
    class Permisos {

        @Test
        @DisplayName("Un CIUDADANO recibe 403 en los nueve endpoints")
        void elCiudadanoNoEntraEnNinguno() throws Exception {
            autenticar(ID_OBJETIVO, Rol.CIUDADANO);

            for (RequestBuilder ruta : rutas()) {
                mockMvc.perform(ruta).andExpect(status().isForbidden());
            }

            // El rol se rechaza antes de llegar al service: no se toca nada.
            verifyNoInteractions(adminService);
        }

        @Test
        @DisplayName("Una PROTECTORA recibe 403 en los nueve endpoints")
        void laProtectoraNoEntraEnNinguno() throws Exception {
            autenticar(20L, Rol.PROTECTORA);

            for (RequestBuilder ruta : rutas()) {
                mockMvc.perform(ruta).andExpect(status().isForbidden());
            }
            verifyNoInteractions(adminService);
        }

        // El caso "sin token" no se prueba aquí a propósito. Esta rebanada corre
        // con addFilters=false, así que un contexto vacío hace que @PreAuthorize
        // lance AuthenticationCredentialsNotFoundException —un 500— en vez del 401
        // que devuelve la cadena de filtros real. Probarlo aquí solo fijaría un
        // artefacto del montaje de pruebas; el 401 de verdad lo comprueba
        // AdminFlujoCompletoTest.sinRolAdminNoSeEntra contra el servidor entero.

        @Test
        @DisplayName("Un ADMIN sí entra en los nueve: la regla es el rol, no una lista negra")
        void elAdminEntraEnLosOcho() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);
            when(adminService.listarUsuarios(any(), any(), any())).thenReturn(paginaVacia());
            when(adminService.bloquear(any(), any(), any())).thenReturn(usuario(false));
            when(adminService.desbloquear(any(), any())).thenReturn(usuario(true));
            when(adminService.listarPendientes()).thenReturn(List.of());
            when(adminService.aprobarProtectora(any(), any()))
                    .thenReturn(protectora(EstadoProtectora.ACTIVA));
            when(adminService.rechazarProtectora(any(), any(), any()))
                    .thenReturn(protectora(EstadoProtectora.RECHAZADA));
            when(adminService.listarReportesPendientes()).thenReturn(List.of());

            for (RequestBuilder ruta : rutas()) {
                mockMvc.perform(ruta).andExpect(status().is2xxSuccessful());
            }
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Contrato de las respuestas")
    class Respuestas {

        @Test
        @DisplayName("El id del administrador sale del token, nunca del cliente")
        void elIdDelAdminSaleDelToken() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);
            when(adminService.bloquear(any(), any(), any())).thenReturn(usuario(false));

            mockMvc.perform(put("/api/admin/usuarios/" + ID_OBJETIVO + "/bloquear")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"motivo\":\"Insultos reiterados\",\"adminId\":999}"))
                    .andExpect(status().isOk());

            // El adminId del cuerpo sobra y se ignora: llega el del token.
            verify(adminService).bloquear(eq(ID_OBJETIVO), eq("Insultos reiterados"), eq(ID_ADMIN));
        }

        @Test
        @DisplayName("Bloquear sin motivo es 400: el cuerpo lo exige @NotBlank")
        void bloquearSinMotivoDa400() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);

            mockMvc.perform(put("/api/admin/usuarios/" + ID_OBJETIVO + "/bloquear")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"motivo\":\"   \"}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(adminService);
        }

        @Test
        @DisplayName("Rechazar una protectora sin motivo es 400")
        void rechazarSinMotivoDa400() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);

            mockMvc.perform(put("/api/admin/protectoras/20/rechazar")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(adminService);
        }

        @Test
        @DisplayName("Autobloquearse devuelve 403 con código AUTOBLOQUEO")
        void autobloqueoDa403ConCodigo() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);
            when(adminService.bloquear(eq(ID_ADMIN), any(), eq(ID_ADMIN)))
                    .thenThrow(new AutobloqueoException(
                            "No puedes bloquear tu propia cuenta: perderías el acceso al panel"));

            mockMvc.perform(put("/api/admin/usuarios/" + ID_ADMIN + "/bloquear")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"motivo\":\"Me castigo\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("AUTOBLOQUEO"));
        }

        @Test
        @DisplayName("DELETE /publicaciones/{id} sin el parámetro tipo es 400, no un borrado a ciegas")
        void retirarSinTipoDa400() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);

            mockMvc.perform(delete("/api/admin/publicaciones/33"))
                    .andExpect(status().isBadRequest());

            // Lo importante no es el 400 sino que no se retire nada: el id 33
            // existe a la vez como alerta, avistamiento y animal.
            verifyNoInteractions(adminService);
        }

        @Test
        @DisplayName("Con el tipo, retirar devuelve 204 y pasa el par (tipo, id) al service")
        void retirarConTipoDa204() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);

            mockMvc.perform(delete("/api/admin/publicaciones/33").param("tipo", "AVISTAMIENTO"))
                    .andExpect(status().isNoContent());

            verify(adminService).retirarPublicacion(
                    eq(TipoContenido.AVISTAMIENTO), eq(33L), eq(ID_ADMIN));
        }

        @Test
        @DisplayName("Un tipo que no está en el enum es 400, no un 500")
        void tipoDesconocidoDa400() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);

            mockMvc.perform(delete("/api/admin/publicaciones/33").param("tipo", "COMENTARIO"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(adminService);
        }

        @Test
        @DisplayName("GET /usuarios pasa los dos filtros tal cual, y omitir 'activo' no es 'activo=false'")
        void losFiltrosLleganAlService() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);
            when(adminService.listarUsuarios(any(), any(), any())).thenReturn(paginaVacia());

            mockMvc.perform(get("/api/admin/usuarios")
                            .param("rol", "PROTECTORA").param("activo", "false"))
                    .andExpect(status().isOk());
            verify(adminService).listarUsuarios(eq(Rol.PROTECTORA), eq(false), any());

            mockMvc.perform(get("/api/admin/usuarios")).andExpect(status().isOk());
            verify(adminService).listarUsuarios(eq(null), eq(null), any());
        }

        @Test
        @DisplayName("Un rol que no está en el enum es 400")
        void rolDesconocidoDa400() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);

            mockMvc.perform(get("/api/admin/usuarios").param("rol", "SUPERADMIN"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(adminService);
        }

        @Test
        @DisplayName("El listado devuelve el email de la cuenta, pero nunca su teléfono")
        void elListadoNoExponeElTelefono() throws Exception {
            autenticar(ID_ADMIN, Rol.ADMIN);
            when(adminService.listarUsuarios(any(), any(), any()))
                    .thenReturn(PaginaResponse.<UsuarioAdminResponse>builder()
                            .content(List.of(usuario(true)))
                            .page(0).size(20).totalElements(1).totalPages(1).last(true)
                            .build());

            mockMvc.perform(get("/api/admin/usuarios"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].email").value("usuario7@test.es"))
                    .andExpect(jsonPath("$.content[0].telefono").doesNotExist())
                    .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());
        }
    }

    // ---------------------------------------------------------------------

    private static void autenticar(Long id, Rol rol) {
        UsuarioPrincipal principal = new UsuarioPrincipal(
                id, "cuenta" + id + "@test.es", "$2a$10$hash", rol, true, null);
        TestSecurityContextHolder.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static PaginaResponse<UsuarioAdminResponse> paginaVacia() {
        return PaginaResponse.<UsuarioAdminResponse>builder()
                .content(List.of()).page(0).size(20).totalElements(0).totalPages(0).last(true)
                .build();
    }

    private static UsuarioAdminResponse usuario(boolean activo) {
        return UsuarioAdminResponse.builder()
                .id(ID_OBJETIVO)
                .nombre("Usuario 7")
                .email("usuario7@test.es")
                .rol(Rol.CIUDADANO)
                .activo(activo)
                .createdAt(Instant.parse("2026-08-20T10:00:00Z"))
                .build();
    }

    private static ProtectoraAdminResponse protectora(EstadoProtectora estado) {
        return ProtectoraAdminResponse.builder()
                .id(20L)
                .nombreOrganizacion("Huellas")
                .estado(estado)
                .usuarioId(30L)
                .usuarioNombre("Refugio")
                .usuarioEmail("refugio@test.es")
                .createdAt(Instant.parse("2026-08-20T10:00:00Z"))
                .build();
    }
}
