package com.rescateanimal.backend.service;

import com.rescateanimal.backend.service.AuditoriaService.Evento;
import com.rescateanimal.backend.service.AuditoriaService.Resultado;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El formato de una línea de auditoría (informe ENS, C2). Que cada evento de
 * autenticación la escribe lo prueban AuthServiceTest y, por HTTP,
 * AuditoriaAutenticacionTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditoriaServiceTest {

    private final AuditoriaService auditoria = new AuditoriaService();

    @AfterEach
    void limpiarPeticion() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("Dentro de una petición: evento, resultado, usuario e IP en clave=valor, en el logger de auditoría")
    void lineaDeUnaPeticion(CapturedOutput salida) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(peticion));

        auditoria.registrar(Evento.LOGIN, Resultado.OK, 42L);

        assertThat(salida).contains("evento=LOGIN resultado=OK usuario=42 ip=203.0.113.7")
                .contains(AuditoriaService.LOGGER);
    }

    @Test
    @DisplayName("Un rechazo va en WARN y un éxito en INFO; sin cuenta, usuario=-; fuera de una petición, ip=-")
    void nivelesYHuecos(CapturedOutput salida) {
        auditoria.registrar(Evento.LOGIN, Resultado.CREDENCIALES_INVALIDAS, null);
        auditoria.registrar(Evento.LOGOUT, Resultado.OK, 7L);

        String[] lineas = salida.getOut().lines()
                .filter(linea -> linea.contains("evento="))
                .toArray(String[]::new);
        assertThat(lineas).hasSize(2);
        assertThat(lineas[0]).contains("WARN").contains("evento=LOGIN resultado=CREDENCIALES_INVALIDAS usuario=- ip=-");
        assertThat(lineas[1]).contains("INFO").contains("evento=LOGOUT resultado=OK usuario=7 ip=-");
    }
}
