package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.AnimalAdopcionDetalleResponse;
import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.AnimalAjenoException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.Tamanyo;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.AnimalAdopcionService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de /api/adopcion (docs/api-rest.md §8).
 *
 * Lo que más importa fijar aquí es el reparto de permisos, que tiene dos
 * mitades: el rol, que impone @PreAuthorize y se prueba de verdad porque la
 * seguridad de método se habilita expresamente más abajo, y la propiedad del
 * animal concreto, que resuelve el service y de la que aquí solo se comprueba
 * que su 403 llega al cliente con el código correcto.
 *
 * Que los filtros del catálogo combinen de verdad se verifica en
 * AnimalAdopcionServiceTest; aquí se comprueba que los parámetros de la URL
 * llegan al service tal y como vienen.
 */
@WebMvcTest(AnimalAdopcionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AnimalAdopcionControllerTest.SeguridadDeMetodo.class)
class AnimalAdopcionControllerTest {

    /**
     * Activa @PreAuthorize dentro de la rebanada.
     *
     * @WebMvcTest no carga SecurityConfig —es un @Configuration normal, y la
     * rebanada solo se queda con la capa web—, así que sin esto la seguridad de
     * método no se aplica y un CIUDADANO entraría en los endpoints de
     * protectora: la prueba pasaría a estar comprobando nada. Se habilita
     * aparte, en vez de importar SecurityConfig entero, para no arrastrar la
     * cadena de filtros y sus dependencias a una prueba que no las necesita.
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SeguridadDeMetodo {
    }

    private static final Long ID_PROTECTORA = 20L;
    private static final Long ID_CIUDADANO = 7L;
    private static final Long ID_ANIMAL = 33L;

    private static final String ANIMAL = """
            {"nombre":"Luna","especie":"PERRO","tamanyo":"MEDIANO",
             "descripcion":"Cariñosa y tranquila",
             "fotoUrl":"https://cdn.test/luna.jpg","zonaGeografica":"Madrid"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnimalAdopcionService animalAdopcionService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;
    @MockitoBean
    private TokenRevocadoService tokenRevocadoService;

    @AfterEach
    void limpiarContexto() {
        TestSecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Gestión por la protectora")
    class Gestion {

        @Test
        @DisplayName("POST -> 201 con Location: un animal sí tiene URL propia")
        void publicarDevuelve201ConLocation() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(animalAdopcionService.publicar(any(), eq(ID_PROTECTORA))).thenReturn(respuesta());

            mockMvc.perform(post("/api/adopcion")
                            .contentType(MediaType.APPLICATION_JSON).content(ANIMAL))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "http://localhost/api/adopcion/33"))
                    .andExpect(jsonPath("$.id").value(ID_ANIMAL))
                    .andExpect(jsonPath("$.estado").value("DISPONIBLE"));
        }

        @Test
        @DisplayName("La protectora sale del token, no del cuerpo")
        void laProtectoraSaleDelToken() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(animalAdopcionService.publicar(any(), any())).thenReturn(respuesta());

            mockMvc.perform(post("/api/adopcion")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nombre":"Luna","especie":"PERRO","tamanyo":"MEDIANO",
                                     "descripcion":"Cariñosa","fotoUrl":"https://cdn.test/l.jpg",
                                     "zonaGeografica":"Madrid","protectoraId":999}
                                    """))
                    .andExpect(status().isCreated());

            verify(animalAdopcionService).publicar(any(), eq(ID_PROTECTORA));
        }

        @Test
        @DisplayName("PUT -> 200 con el animal ya modificado")
        void actualizarDevuelve200() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(animalAdopcionService.actualizar(eq(ID_ANIMAL), any(), eq(ID_PROTECTORA)))
                    .thenReturn(respuesta());

            mockMvc.perform(put("/api/adopcion/{id}", ID_ANIMAL)
                            .contentType(MediaType.APPLICATION_JSON).content(ANIMAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ID_ANIMAL));
        }

        @Test
        @DisplayName("DELETE -> 204 sin cuerpo")
        void retirarDevuelve204() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);

            mockMvc.perform(delete("/api/adopcion/{id}", ID_ANIMAL))
                    .andExpect(status().isNoContent())
                    .andExpect(jsonPath("$").doesNotExist());

            verify(animalAdopcionService).retirar(ID_ANIMAL, ID_PROTECTORA);
        }

        @Test
        @DisplayName("PUT /adoptado -> 200 con estado ADOPTADO (RF-20)")
        void confirmarAdopcionDevuelve200() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(animalAdopcionService.confirmarAdopcion(ID_ANIMAL, ID_PROTECTORA))
                    .thenReturn(adoptado());

            mockMvc.perform(put("/api/adopcion/{id}/adoptado", ID_ANIMAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.estado").value("ADOPTADO"));
        }

        @Test
        @DisplayName("Animal de otra protectora -> 403 ANIMAL_AJENO")
        void animalAjenoDevuelve403() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(animalAdopcionService.actualizar(any(), any(), any())).thenThrow(
                    new AnimalAjenoException("Este animal pertenece a otra protectora"));

            mockMvc.perform(put("/api/adopcion/{id}", ID_ANIMAL)
                            .contentType(MediaType.APPLICATION_JSON).content(ANIMAL))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("ANIMAL_AJENO"));
        }

        @Test
        @DisplayName("Animal inexistente -> 404")
        void animalInexistenteDevuelve404() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(animalAdopcionService.actualizar(any(), any(), any()))
                    .thenThrow(new ResourceNotFoundException("El animal 33 no existe"));

            mockMvc.perform(put("/api/adopcion/{id}", ID_ANIMAL)
                            .contentType(MediaType.APPLICATION_JSON).content(ANIMAL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        }

        @Test
        @DisplayName("DELETE de un animal ajeno -> 403 ANIMAL_AJENO")
        void retirarAjenoDevuelve403() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            org.mockito.Mockito.doThrow(new AnimalAjenoException("Este animal pertenece a otra protectora"))
                    .when(animalAdopcionService).retirar(any(), any());

            mockMvc.perform(delete("/api/adopcion/{id}", ID_ANIMAL))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("ANIMAL_AJENO"));
        }

        @Test
        @DisplayName("DELETE de un animal inexistente -> 404")
        void retirarInexistenteDevuelve404() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            org.mockito.Mockito.doThrow(new ResourceNotFoundException("El animal 33 no existe"))
                    .when(animalAdopcionService).retirar(any(), any());

            mockMvc.perform(delete("/api/adopcion/{id}", ID_ANIMAL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        }

        @Test
        @DisplayName("PUT /adoptado sobre un animal ajeno -> 403 ANIMAL_AJENO")
        void confirmarAjenoDevuelve403() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(animalAdopcionService.confirmarAdopcion(any(), any()))
                    .thenThrow(new AnimalAjenoException("Este animal pertenece a otra protectora"));

            mockMvc.perform(put("/api/adopcion/{id}/adoptado", ID_ANIMAL))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("ANIMAL_AJENO"));
        }

        @Test
        @DisplayName("PUT /adoptado sobre un animal inexistente -> 404")
        void confirmarInexistenteDevuelve404() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);
            when(animalAdopcionService.confirmarAdopcion(any(), any()))
                    .thenThrow(new ResourceNotFoundException("El animal 33 no existe"));

            mockMvc.perform(put("/api/adopcion/{id}/adoptado", ID_ANIMAL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        }
    }

    /**
     * El rol lo impone @PreAuthorize. Se prueba desde HTTP porque la seguridad
     * de método no depende de la cadena de filtros: sigue aplicándose aunque
     * estos estén desactivados, resolviendo el rol contra el SecurityContext.
     */
    @Nested
    @DisplayName("Un ciudadano no gestiona el catálogo")
    class RolInsuficiente {

        @Test
        @DisplayName("POST siendo CIUDADANO -> 403 y el service ni se toca")
        void ciudadanoNoPuedePublicar() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);

            mockMvc.perform(post("/api/adopcion")
                            .contentType(MediaType.APPLICATION_JSON).content(ANIMAL))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));

            verifyNoInteractions(animalAdopcionService);
        }

        @Test
        @DisplayName("PUT siendo CIUDADANO -> 403")
        void ciudadanoNoPuedeEditar() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);

            mockMvc.perform(put("/api/adopcion/{id}", ID_ANIMAL)
                            .contentType(MediaType.APPLICATION_JSON).content(ANIMAL))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(animalAdopcionService);
        }

        @Test
        @DisplayName("DELETE siendo CIUDADANO -> 403 y no se retira nada")
        void ciudadanoNoPuedeRetirar() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);

            mockMvc.perform(delete("/api/adopcion/{id}", ID_ANIMAL))
                    .andExpect(status().isForbidden());

            verify(animalAdopcionService, never()).retirar(any(), any());
        }

        @Test
        @DisplayName("PUT /adoptado siendo CIUDADANO -> 403: no confirma quien adopta")
        void ciudadanoNoConfirmaLaAdopcion() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);

            mockMvc.perform(put("/api/adopcion/{id}/adoptado", ID_ANIMAL))
                    .andExpect(status().isForbidden());

            verify(animalAdopcionService, never()).confirmarAdopcion(any(), any());
        }

        @Test
        @DisplayName("Pero sí puede consultar el catálogo y el detalle: son su escaparate")
        void elCiudadanoSiConsulta() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.catalogo(any(), any(), any(), any())).thenReturn(pagina());
            when(animalAdopcionService.detalle(ID_ANIMAL)).thenReturn(detalle());

            mockMvc.perform(get("/api/adopcion")).andExpect(status().isOk());
            mockMvc.perform(get("/api/adopcion/{id}", ID_ANIMAL)).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Catálogo y detalle")
    class Consultas {

        @Test
        @DisplayName("GET -> 200 con la página en la forma documentada en §1")
        void catalogoDevuelve200() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.catalogo(any(), any(), any(), any())).thenReturn(pagina());

            mockMvc.perform(get("/api/adopcion"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(ID_ANIMAL))
                    .andExpect(jsonPath("$.content[0].protectoraNombre").value("Protectora de prueba"))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.pageable").doesNotExist());
        }

        @Test
        @DisplayName("Los tres filtros llegan al service tal como vienen en la URL")
        void losFiltrosLleganAlService() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.catalogo(any(), any(), any(), any())).thenReturn(pagina());

            mockMvc.perform(get("/api/adopcion")
                            .param("especie", "perro")
                            .param("tamanyo", "PEQUENO")
                            .param("zona", "Madrid"))
                    .andExpect(status().isOk());

            verify(animalAdopcionService)
                    .catalogo(eq("perro"), eq(Tamanyo.PEQUENO), eq("Madrid"), any());
        }

        @Test
        @DisplayName("Sin filtros llegan los tres a null")
        void sinFiltrosLleganNulos() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.catalogo(any(), any(), any(), any())).thenReturn(pagina());

            mockMvc.perform(get("/api/adopcion")).andExpect(status().isOk());

            verify(animalAdopcionService).catalogo(isNull(), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("Un tamanyo que no es del enum -> 400, no 500")
        void tamanyoDesconocidoDevuelve400() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);

            mockMvc.perform(get("/api/adopcion").param("tamanyo", "ENORME"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(animalAdopcionService);
        }

        @Test
        @DisplayName("GET /{id} -> 200 con la protectora, sin email ni teléfono (RNF-10)")
        void detalleDevuelve200SinDatosPersonales() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.detalle(ID_ANIMAL)).thenReturn(detalle());

            mockMvc.perform(get("/api/adopcion/{id}", ID_ANIMAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.protectoraNombre").value("Protectora de prueba"))
                    .andExpect(jsonPath("$.protectoraValoracionMedia").value(4.5))
                    .andExpect(jsonPath("$.protectoraEmail").doesNotExist())
                    .andExpect(jsonPath("$.protectoraTelefono").doesNotExist())
                    .andExpect(jsonPath("$.usuario").doesNotExist());
        }

        @Test
        @DisplayName("GET /feed -> 200 con una lista, no una página: se consume tarjeta a tarjeta")
        void feedDevuelveLista() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.feed(any(), any(), any(), eq(ID_CIUDADANO)))
                    .thenReturn(List.of(respuesta()));

            mockMvc.perform(get("/api/adopcion/feed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].id").value(ID_ANIMAL))
                    .andExpect(jsonPath("$.content").doesNotExist())
                    .andExpect(jsonPath("$.totalElements").doesNotExist());
        }

        @Test
        @DisplayName("El feed se pide siempre para el usuario del token")
        void elFeedUsaElUsuarioDelToken() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.feed(any(), any(), any(), any())).thenReturn(List.of());

            mockMvc.perform(get("/api/adopcion/feed")).andExpect(status().isOk());

            verify(animalAdopcionService).feed(isNull(), isNull(), isNull(), eq(ID_CIUDADANO));
        }

        @Test
        @DisplayName("El feed admite los mismos filtros combinables que el catálogo")
        void elFeedAdmiteLosMismosFiltros() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.feed(any(), any(), any(), any())).thenReturn(List.of());

            mockMvc.perform(get("/api/adopcion/feed")
                            .param("especie", "perro")
                            .param("tamanyo", "PEQUENO")
                            .param("zona", "Madrid"))
                    .andExpect(status().isOk());

            verify(animalAdopcionService)
                    .feed(eq("perro"), eq(Tamanyo.PEQUENO), eq("Madrid"), eq(ID_CIUDADANO));
        }

        @Test
        @DisplayName("Feed agotado -> 200 con lista vacía, no 404")
        void feedVacioDevuelve200() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.feed(any(), any(), any(), any())).thenReturn(List.of());

            mockMvc.perform(get("/api/adopcion/feed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("'feed' no se confunde con un id: no llega a la ruta del detalle")
        void feedNoSeConfundeConUnId() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.feed(any(), any(), any(), any())).thenReturn(List.of());

            mockMvc.perform(get("/api/adopcion/feed")).andExpect(status().isOk());

            verify(animalAdopcionService, never()).detalle(any());
        }

        @Test
        @DisplayName("Un tamanyo inválido en el feed -> 400, igual que en el catálogo")
        void feedConTamanyoInvalidoDevuelve400() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);

            mockMvc.perform(get("/api/adopcion/feed").param("tamanyo", "ENORME"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(animalAdopcionService);
        }

        @Test
        @DisplayName("GET /{id} inexistente -> 404")
        void detalleInexistenteDevuelve404() throws Exception {
            autenticar(ID_CIUDADANO, Rol.CIUDADANO);
            when(animalAdopcionService.detalle(any()))
                    .thenThrow(new ResourceNotFoundException("El animal 33 no existe"));

            mockMvc.perform(get("/api/adopcion/{id}", ID_ANIMAL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        }
    }

    @Nested
    @DisplayName("Validación del cuerpo")
    class Validacion {

        @Test
        @DisplayName("Sin nombre -> 400 con el campo señalado")
        void sinNombreDevuelve400() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);

            mockMvc.perform(post("/api/adopcion")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"especie":"PERRO","tamanyo":"MEDIANO","descripcion":"Cariñosa",
                                     "fotoUrl":"https://cdn.test/l.jpg","zonaGeografica":"Madrid"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                    .andExpect(jsonPath("$.errores.nombre").exists());
        }

        @Test
        @DisplayName("Sin tamaño -> 400: es criterio de filtrado, no puede faltar (RF-22)")
        void sinTamanyoDevuelve400() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);

            mockMvc.perform(post("/api/adopcion")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nombre":"Luna","especie":"PERRO","descripcion":"Cariñosa",
                                     "fotoUrl":"https://cdn.test/l.jpg","zonaGeografica":"Madrid"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores.tamanyo").exists());
        }

        @Test
        @DisplayName("Sin foto -> 400: el swipe de adopción se decide mirando (RF-09)")
        void sinFotoDevuelve400() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);

            mockMvc.perform(post("/api/adopcion")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nombre":"Luna","especie":"PERRO","tamanyo":"MEDIANO",
                                     "descripcion":"Cariñosa","zonaGeografica":"Madrid"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores.fotoUrl").exists());
        }

        @Test
        @DisplayName("El PUT exige el cuerpo completo: reemplaza, no parchea")
        void elPutExigeTodosLosCampos() throws Exception {
            autenticar(ID_PROTECTORA, Rol.PROTECTORA);

            mockMvc.perform(put("/api/adopcion/{id}", ID_ANIMAL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nombre":"Solo el nombre"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
    }

    // ---------------------------------------------------------------------

    private static void autenticar(Long id, Rol rol) {
        UsuarioPrincipal principal = new UsuarioPrincipal(
                id, "cuenta" + id + "@test.es", "$2a$10$hash", rol, true, null);
        TestSecurityContextHolder.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static AnimalAdopcionResponse respuesta() {
        return respuesta("DISPONIBLE");
    }

    private static AnimalAdopcionResponse adoptado() {
        return respuesta("ADOPTADO");
    }

    private static AnimalAdopcionResponse respuesta(String estado) {
        return AnimalAdopcionResponse.builder()
                .id(ID_ANIMAL)
                .nombre("Luna")
                .especie("PERRO")
                .tamanyo(Tamanyo.MEDIANO)
                .descripcion("Cariñosa y tranquila")
                .fotoUrl("https://cdn.test/luna.jpg")
                .zonaGeografica("Madrid")
                .estado(estado)
                .protectoraId(70L)
                .protectoraNombre("Protectora de prueba")
                .createdAt(Instant.now())
                .build();
    }

    private static PaginaResponse<AnimalAdopcionResponse> pagina() {
        return PaginaResponse.<AnimalAdopcionResponse>builder()
                .content(List.of(respuesta()))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .last(true)
                .build();
    }

    private static AnimalAdopcionDetalleResponse detalle() {
        return AnimalAdopcionDetalleResponse.builder()
                .id(ID_ANIMAL)
                .nombre("Luna")
                .especie("PERRO")
                .tamanyo(Tamanyo.MEDIANO)
                .descripcion("Cariñosa y tranquila")
                .fotoUrl("https://cdn.test/luna.jpg")
                .zonaGeografica("Madrid")
                .estado("DISPONIBLE")
                .createdAt(Instant.now())
                .protectoraId(70L)
                .protectoraNombre("Protectora de prueba")
                .protectoraDireccion("Calle Mayor 1")
                .protectoraZonaGeografica("Madrid")
                .protectoraValoracionMedia(new BigDecimal("4.50"))
                .build();
    }
}
