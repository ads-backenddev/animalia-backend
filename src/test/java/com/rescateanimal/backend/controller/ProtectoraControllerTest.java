package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.ContactoProtectoraResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ProtectoraDetalleResponse;
import com.rescateanimal.backend.dto.response.ProtectoraDirectorioResponse;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.service.ControlIntentosService;
import com.rescateanimal.backend.service.ProtectoraService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del directorio de protectoras (RF-06, docs/api-rest.md §9).
 *
 * LO QUE MÁS IMPORTA FIJAR AQUÍ ES LA FORMA DEL JSON, y en concreto las
 * AUSENCIAS. Tres campos de esta respuesta se omiten en lugar de viajar como
 * null —el teléfono, la dirección y la media— y cada uno por su motivo: los dos
 * primeros porque detrás de la protectora puede haber una persona a la que no se
 * publica, y la media porque un cero se leería como "pésima". Que se omitan es
 * la mitad del contrato y solo se ve serializando de verdad: en el service son
 * nulos y nada distingue un null omitido de un null enviado.
 *
 * Que un 404 sea un 404 y no un 403 se decide en el service; aquí se comprueba
 * que llega al cliente con el código correcto y sin decir por qué.
 */
@WebMvcTest(ProtectoraController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProtectoraControllerTest {

    private static final Long ID_PROTECTORA = 30L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProtectoraService protectoraService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;
    @MockitoBean
    private TokenRevocadoService tokenRevocadoService;
    @MockitoBean
    private ControlIntentosService controlIntentosService;

    // =====================================================================

    @Nested
    @DisplayName("GET /api/protectoras")
    class Directorio {

        @Test
        @DisplayName("Devuelve la página con el contacto de cada protectora")
        void devuelveLaPagina() throws Exception {
            when(protectoraService.directorio(any(), any())).thenReturn(pagina(entidad()));

            mockMvc.perform(get("/api/protectoras"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].nombreOrganizacion")
                            .value("Huellas de Getafe"))
                    .andExpect(jsonPath("$.content[0].zonaGeografica").value("Madrid"))
                    .andExpect(jsonPath("$.content[0].contacto.email")
                            .value("contacto@huellas.es"))
                    .andExpect(jsonPath("$.content[0].contacto.telefono").value("600111222"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("El filtro de zona llega al service tal cual lo escribió el cliente")
        void elFiltroDeZonaLlega() throws Exception {
            when(protectoraService.directorio(any(), any())).thenReturn(pagina(entidad()));

            mockMvc.perform(get("/api/protectoras").param("zona", "Bajo Deba"))
                    .andExpect(status().isOk());

            // Normalizarlo es cosa del service, que es quien lo convierte en
            // patrón LIKE; el controller no toca el valor.
            verify(protectoraService).directorio(eq("Bajo Deba"), any());
        }

        @Test
        @DisplayName("Sin filtro de zona el service recibe null, no una cadena vacía")
        void sinFiltroLlegaNull() throws Exception {
            when(protectoraService.directorio(any(), any())).thenReturn(pagina(entidad()));

            mockMvc.perform(get("/api/protectoras")).andExpect(status().isOk());

            verify(protectoraService).directorio(isNull(), any());
        }

        @Test
        @DisplayName("page y size llegan al service")
        void laPaginacionLlega() throws Exception {
            when(protectoraService.directorio(any(), any())).thenReturn(pagina(entidad()));

            mockMvc.perform(get("/api/protectoras").param("page", "2").param("size", "5"))
                    .andExpect(status().isOk());

            verify(protectoraService).directorio(any(), paginacionDe(2, 5));
        }

        /**
         * El teléfono de una persona física no se publica. Se comprueba sobre el
         * JSON y no sobre el DTO porque lo que hay que fijar es que el campo
         * DESAPARECE, no que valga null: mandar "telefono": null delataría que hay
         * un número y que se ha decidido no darlo.
         */
        @Test
        @DisplayName("Un teléfono ausente desaparece del JSON, no viaja como null")
        void elTelefonoAusenteDesaparece() throws Exception {
            when(protectoraService.directorio(any(), any())).thenReturn(pagina(personaFisica()));

            mockMvc.perform(get("/api/protectoras"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].contacto.telefono").doesNotExist())
                    .andExpect(jsonPath("$.content[0].contacto.email").exists())
                    .andExpect(jsonPath("$.content[0].esPersonaFisica").value(true));
        }

        @Test
        @DisplayName("Una protectora sin valoraciones no manda media, pero sí el total en cero")
        void laMediaAusenteDesaparece() throws Exception {
            when(protectoraService.directorio(any(), any())).thenReturn(pagina(personaFisica()));

            mockMvc.perform(get("/api/protectoras"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].valoracionMedia").doesNotExist())
                    .andExpect(jsonPath("$.content[0].totalValoraciones").value(0));
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("GET /api/protectoras/{id}")
    class Ficha {

        @Test
        @DisplayName("Devuelve la ficha con sus animales y sus valoraciones")
        void devuelveLaFicha() throws Exception {
            when(protectoraService.detalle(anyLong(), any())).thenReturn(ficha(false));

            mockMvc.perform(get("/api/protectoras/{id}", ID_PROTECTORA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ID_PROTECTORA))
                    .andExpect(jsonPath("$.direccion").value("Calle Mayor 3"))
                    .andExpect(jsonPath("$.valoracionMedia").value(4.5))
                    .andExpect(jsonPath("$.totalValoraciones").value(8))
                    .andExpect(jsonPath("$.animales.content").isArray())
                    .andExpect(jsonPath("$.valoraciones").isArray());
        }

        @Test
        @DisplayName("La dirección de una persona física no aparece en el JSON")
        void laDireccionDeUnParticularDesaparece() throws Exception {
            when(protectoraService.detalle(anyLong(), any())).thenReturn(ficha(true));

            mockMvc.perform(get("/api/protectoras/{id}", ID_PROTECTORA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.direccion").doesNotExist())
                    .andExpect(jsonPath("$.contacto.telefono").doesNotExist());
        }

        @Test
        @DisplayName("Una protectora que no existe da 404 con NO_ENCONTRADO")
        void laInexistenteDaNotFound() throws Exception {
            when(protectoraService.detalle(anyLong(), any()))
                    .thenThrow(new ResourceNotFoundException("La protectora 99 no existe"));

            mockMvc.perform(get("/api/protectoras/{id}", 99L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        }

        @Test
        @DisplayName("page y size paginan los animales de la ficha")
        void laPaginacionEsDeLosAnimales() throws Exception {
            when(protectoraService.detalle(anyLong(), any())).thenReturn(ficha(false));

            mockMvc.perform(get("/api/protectoras/{id}", ID_PROTECTORA)
                            .param("page", "1").param("size", "4"))
                    .andExpect(status().isOk());

            verify(protectoraService).detalle(eq(ID_PROTECTORA), paginacionDe(1, 4));
        }

        @Test
        @DisplayName("Un id que no es un número da 400, no un 500")
        void unIdQueNoEsNumeroDaBadRequest() throws Exception {
            mockMvc.perform(get("/api/protectoras/{id}", "no-soy-un-id"))
                    .andExpect(status().isBadRequest());
        }
    }

    // =====================================================================
    // Apoyo
    // =====================================================================

    /**
     * Comprueba página y tamaño del Pageable que recibe el service, sin atarse al
     * resto del objeto.
     */
    private static Pageable paginacionDe(int pagina, int tamanyo) {
        return org.mockito.ArgumentMatchers.argThat(peticion -> {
            assertThat(peticion.getPageNumber()).isEqualTo(pagina);
            assertThat(peticion.getPageSize()).isEqualTo(tamanyo);
            return true;
        });
    }

    private static PaginaResponse<ProtectoraDirectorioResponse> pagina(
            ProtectoraDirectorioResponse fila) {

        return PaginaResponse.<ProtectoraDirectorioResponse>builder()
                .content(List.of(fila))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .last(true)
                .build();
    }

    private static ProtectoraDirectorioResponse entidad() {
        return ProtectoraDirectorioResponse.builder()
                .id(ID_PROTECTORA)
                .nombreOrganizacion("Huellas de Getafe")
                .zonaGeografica("Madrid")
                .esPersonaFisica(false)
                .valoracionMedia(new BigDecimal("4.50"))
                .totalValoraciones(8L)
                .contacto(ContactoProtectoraResponse.builder()
                        .email("contacto@huellas.es")
                        .telefono("600111222")
                        .build())
                .build();
    }

    /** Sin teléfono y sin valoraciones: los dos campos que deben desaparecer. */
    private static ProtectoraDirectorioResponse personaFisica() {
        return ProtectoraDirectorioResponse.builder()
                .id(ID_PROTECTORA)
                .nombreOrganizacion("Refugio de Marta")
                .zonaGeografica("Bajo Deba")
                .esPersonaFisica(true)
                .valoracionMedia(null)
                .totalValoraciones(0L)
                .contacto(ContactoProtectoraResponse.builder()
                        .email("marta@refugio.es")
                        .telefono(null)
                        .build())
                .build();
    }

    private static ProtectoraDetalleResponse ficha(boolean esPersonaFisica) {
        return ProtectoraDetalleResponse.builder()
                .id(ID_PROTECTORA)
                .nombreOrganizacion("Huellas de Getafe")
                .zonaGeografica("Madrid")
                .direccion(esPersonaFisica ? null : "Calle Mayor 3")
                .esPersonaFisica(esPersonaFisica)
                .createdAt(Instant.parse("2026-01-10T09:00:00Z"))
                .contacto(ContactoProtectoraResponse.builder()
                        .email("contacto@huellas.es")
                        .telefono(esPersonaFisica ? null : "600111222")
                        .build())
                .valoracionMedia(new BigDecimal("4.50"))
                .totalValoraciones(8L)
                .animales(PaginaResponse.<AnimalAdopcionResponse>builder()
                        .content(List.of())
                        .page(0)
                        .size(20)
                        .totalElements(0)
                        .totalPages(0)
                        .last(true)
                        .build())
                .valoraciones(List.of())
                .build();
    }
}
