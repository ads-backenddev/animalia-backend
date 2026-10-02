package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.response.FotoResponse;
import com.rescateanimal.backend.exception.FotoNoValidaException;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UserDetailsServiceImpl;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.ControlIntentosService;
import com.rescateanimal.backend.service.FotoService;
import com.rescateanimal.backend.service.TokenRevocadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de POST /api/fotos.
 *
 * El 413 de verdad —el que corta Spring al leer el multipart— no se puede
 * provocar con MockMvc, que no pasa por el resolver de multipart; aquí se
 * comprueba su traducción, y FotoSubidaFlujoCompletoTest lo provoca de verdad.
 */
@WebMvcTest(FotoController.class)
@AutoConfigureMockMvc(addFilters = false)
class FotoControllerTest {

    private static final Long ID_USUARIO = 7L;
    private static final String URL = "https://fotos.test/fotos/3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FotoService fotoService;
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
    @DisplayName("Subida válida -> 201 con la URL en el cuerpo y en Location")
    void subidaValida() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(fotoService.subir(any(), eq(ID_USUARIO))).thenReturn(FotoResponse.builder().url(URL).build());

        mockMvc.perform(multipart("/api/fotos").file(archivo("archivo", bytes)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", URL))
                .andExpect(jsonPath("$.url").value(URL));

        verify(fotoService).subir(eq(bytes), eq(ID_USUARIO));
    }

    @Test
    @DisplayName("Contenido que no es imagen -> 400 FOTO_NO_VALIDA con el motivo")
    void fotoNoValida() throws Exception {
        when(fotoService.subir(any(), any())).thenThrow(new FotoNoValidaException("La foto debe ser una imagen JPEG o PNG"));

        mockMvc.perform(multipart("/api/fotos").file(archivo("archivo", new byte[]{1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("FOTO_NO_VALIDA"))
                .andExpect(jsonPath("$.mensaje").value("La foto debe ser una imagen JPEG o PNG"));
    }

    @Test
    @DisplayName("Sin la parte \"archivo\" -> 400")
    void sinArchivo() throws Exception {
        mockMvc.perform(multipart("/api/fotos").file(archivo("foto", new byte[]{1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PETICION_INVALIDA"));
    }

    @Test
    @DisplayName("Archivo por encima del límite -> 413 FOTO_DEMASIADO_GRANDE")
    void demasiadoGrande() throws Exception {
        when(fotoService.subir(any(), any())).thenThrow(new MaxUploadSizeExceededException(5L * 1024 * 1024));

        mockMvc.perform(multipart("/api/fotos").file(archivo("archivo", new byte[]{1})))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.codigo").value("FOTO_DEMASIADO_GRANDE"));
    }

    private static MockMultipartFile archivo(String parte, byte[] bytes) {
        return new MockMultipartFile(parte, "foto.jpg", "image/jpeg", bytes);
    }
}
