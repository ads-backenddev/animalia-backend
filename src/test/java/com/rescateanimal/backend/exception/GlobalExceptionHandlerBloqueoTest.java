package com.rescateanimal.backend.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rescateanimal.backend.dto.response.ErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El manejador de los fallos de bloqueo: al cliente siempre 409 RECURSO_OCUPADO,
 * y al log el nivel que corresponde según el SQLState de PostgreSQL.
 *
 * Las tres pruebas usan la MISMA clase de excepción, con SQLState distinto: lo
 * que se comprueba es que el manejador decide por el estado y no por el tipo.
 * La espera vencida de verdad, con la excepción que llega de PostgreSQL, la
 * prueba AlertaEsperaBloqueoTest.
 */
class GlobalExceptionHandlerBloqueoTest {

    private final GlobalExceptionHandler manejador = new GlobalExceptionHandler();
    private final ServletWebRequest peticion =
            new ServletWebRequest(new MockHttpServletRequest("PUT", "/api/alertas/5"));
    private final Logger log = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> registro = new ListAppender<>();

    @BeforeEach
    void escucharElLog() {
        registro.start();
        log.addAppender(registro);
    }

    @AfterEach
    void dejarDeEscuchar() {
        log.detachAppender(registro);
    }

    @Test
    @DisplayName("Espera vencida (55P03): 409 RECURSO_OCUPADO y warn, sin traza")
    void esperaVencida() {
        ResponseEntity<ErrorResponse> respuesta = manejar("55P03");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(respuesta.getBody().getCodigo()).isEqualTo("RECURSO_OCUPADO");
        assertThat(registro.list).singleElement().satisfies(evento -> {
            assertThat(evento.getLevel()).isEqualTo(Level.WARN);
            assertThat(evento.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("Interbloqueo (40P01): el mismo 409 al cliente, pero error en el log y con la traza")
    void interbloqueo() {
        ResponseEntity<ErrorResponse> respuesta = manejar("40P01");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(respuesta.getBody().getCodigo()).isEqualTo("RECURSO_OCUPADO");
        assertThat(registro.list).singleElement().satisfies(evento -> {
            assertThat(evento.getLevel()).isEqualTo(Level.ERROR);
            assertThat(evento.getFormattedMessage()).contains("Interbloqueo");
            assertThat(evento.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    @DisplayName("Cualquier otro SQLState tampoco se espera: error en el log")
    void otroEstado() {
        manejar("40001");

        assertThat(registro.list).singleElement()
                .satisfies(evento -> assertThat(evento.getLevel()).isEqualTo(Level.ERROR));
    }

    /** Como llega de Spring: la excepción de Spring, envolviendo el SQLException del driver. */
    private ResponseEntity<ErrorResponse> manejar(String sqlState) {
        SQLException deLaBase = new SQLException("fallo de bloqueo", sqlState);
        return manejador.recursoOcupado(new CannotAcquireLockException("No se pudo bloquear la fila", deLaBase),
                peticion);
    }
}
