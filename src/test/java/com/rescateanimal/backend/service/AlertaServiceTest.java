package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.ActualizarAlertaRequest;
import com.rescateanimal.backend.dto.request.CrearAlertaRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.AlertaDetalleResponse;
import com.rescateanimal.backend.dto.response.AlertaResponse;
import com.rescateanimal.backend.dto.response.AvistamientoResponse;
import com.rescateanimal.backend.dto.response.EventoMapaResponse;
import com.rescateanimal.backend.exception.AlertaDuplicadaException;
import com.rescateanimal.backend.exception.FotoUrlNoValidaException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas de la lógica de alertas: detección de duplicados (RF-25), mapa en
 * tiempo real (RF-04) y cierre (RF-14).
 */
@ExtendWith(MockitoExtension.class)
class AlertaServiceTest {

    private static final double RADIO_METROS = 500.0;
    private static final int VENTANA_HORAS = 24;
    private static final double RADIO_MAPA_MAXIMO = 50_000.0;

    /** Puerta del Sol, Madrid. */
    private static final double LAT = 40.4168;
    private static final double LNG = -3.7038;

    @Mock
    private FotoService fotoService;
    @Mock
    private AvistamientoService avistamientoService;
    @Mock
    private AlertaRepository alertaRepository;
    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private NotificacionService notificacionService;
    @Mock
    private SimpMessagingTemplate mensajeria;

    private AlertaService alertaService;

    @BeforeEach
    void prepararServicio() {
        alertaService = new AlertaService(alertaRepository, usuarioRepository,
                notificacionService, fotoService, avistamientoService, mensajeria,
                RADIO_METROS, VENTANA_HORAS, RADIO_MAPA_MAXIMO);
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Detección de alertas duplicadas (RF-25)")
    class Duplicados {

        @Test
        @DisplayName("Sin coincidencias la alerta se publica")
        void publicaCuandoNoHayDuplicado() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> {
                Alerta a = i.getArgument(0);
                a.setId(99L);
                return a;
            });

            AlertaResponse respuesta = alertaService.publicar(peticion(), 1L);

            assertThat(respuesta.getId()).isEqualTo(99L);
            assertThat(respuesta.getEstado()).isEqualTo(EstadoAlerta.ACTIVA);
            verify(alertaRepository).save(any(Alerta.class));
        }

        @Test
        @DisplayName("Con una alerta similar se bloquea la publicación y no se guarda nada")
        void bloqueaLaPublicacionDuplicada() {
            Alerta existente = alertaExistente(42L);
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.of(existente));
            when(alertaRepository.distanciaMetros(42L, LAT, LNG)).thenReturn(180.4);

            assertThatThrownBy(() -> alertaService.publicar(peticion(), 1L))
                    .isInstanceOf(AlertaDuplicadaException.class);

            verify(alertaRepository, never()).save(any());
            verify(notificacionService, never()).notificarAlertaCercana(any());
            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
        }

        @Test
        @DisplayName("El 409 incluye la alerta existente para poder redirigir al ciudadano")
        void informaDeLaAlertaExistente() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.of(alertaExistente(42L)));
            when(alertaRepository.distanciaMetros(42L, LAT, LNG)).thenReturn(180.4);

            assertThatThrownBy(() -> alertaService.publicar(peticion(), 1L))
                    .isInstanceOf(AlertaDuplicadaException.class)
                    .satisfies(e -> {
                        var info = ((AlertaDuplicadaException) e).getAlertaExistente();
                        assertThat(info.getId()).isEqualTo(42L);
                        assertThat(info.getEspecie()).isEqualTo("perro");
                        assertThat(info.getDistanciaMetros()).isEqualTo(180L);
                    });
        }

        @Test
        @DisplayName("La búsqueda usa el radio y la ventana temporal configurados: 500 m y 24 h")
        void consultaConLosUmbralesDelRequisito() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));
            Instant antesDeLlamar = Instant.now().minus(VENTANA_HORAS, ChronoUnit.HOURS);

            alertaService.publicar(peticion(), 1L);

            ArgumentCaptor<Double> radio = ArgumentCaptor.forClass(Double.class);
            ArgumentCaptor<Instant> desde = ArgumentCaptor.forClass(Instant.class);
            verify(alertaRepository).buscarSimilar(eq("perro"), eq(LAT), eq(LNG),
                    radio.capture(), desde.capture(), eq(0L));

            assertThat(radio.getValue()).isEqualTo(500.0);
            assertThat(desde.getValue()).isBetween(antesDeLlamar.minus(1, ChronoUnit.MINUTES),
                    antesDeLlamar.plus(1, ChronoUnit.MINUTES));
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Geometría")
    class Geometria {

        @Test
        @DisplayName("La latitud y la longitud se guardan en el orden que espera JTS: x=longitud, y=latitud")
        void guardaElPuntoConElOrdenCorrecto() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));

            alertaService.publicar(peticion(), 1L);

            ArgumentCaptor<Alerta> guardada = ArgumentCaptor.forClass(Alerta.class);
            verify(alertaRepository).save(guardada.capture());
            Point punto = guardada.getValue().getUltimaUbicacion();

            assertThat(punto.getY()).isEqualTo(LAT);
            assertThat(punto.getX()).isEqualTo(LNG);
            assertThat(punto.getSRID()).isEqualTo(4326);
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Mapa en tiempo real (RF-04)")
    class Mapa {

        @Test
        @DisplayName("Publicar una alerta emite un evento ALTA en /topic/mapa")
        void emiteEventoAlPublicar() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> {
                Alerta a = i.getArgument(0);
                a.setId(99L);
                return a;
            });

            alertaService.publicar(peticion(), 1L);

            ArgumentCaptor<EventoMapaResponse> evento =
                    ArgumentCaptor.forClass(EventoMapaResponse.class);
            verify(mensajeria).convertAndSend(eq(AlertaService.CANAL_MAPA), evento.capture());

            assertThat(evento.getValue().getAccion()).isEqualTo(EventoMapaResponse.Accion.ALTA);
            assertThat(evento.getValue().getAlerta().getId()).isEqualTo(99L);
            assertThat(evento.getValue().getAlerta().getUbicacion().getLatitud()).isEqualTo(LAT);
        }

        @Test
        @DisplayName("Si falla la mensajería la alerta se publica igual: ya está guardada")
        void unFalloDeMensajeriaNoTumbaLaPublicacion() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));
            doThrow(new IllegalStateException("broker caído"))
                    .when(mensajeria).convertAndSend(anyString(), any(EventoMapaResponse.class));

            AlertaResponse respuesta = alertaService.publicar(peticion(), 1L);

            assertThat(respuesta).isNotNull();
        }

        @Test
        @DisplayName("Si falla la creación de notificaciones la alerta se publica igual")
        void unFalloDeNotificacionesNoTumbaLaPublicacion() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));
            doThrow(new IllegalStateException("no se pudo guardar la notificación"))
                    .when(notificacionService).notificarAlertaCercana(any());

            AlertaResponse respuesta = alertaService.publicar(peticion(), 1L);

            assertThat(respuesta).isNotNull();
            // El aviso a la zona (RF-05) es una consecuencia, no un requisito de
            // la publicación (RF-03): el mapa se refresca de todos modos.
            verify(mensajeria).convertAndSend(eq(AlertaService.CANAL_MAPA), any(EventoMapaResponse.class));
        }

        @Test
        @DisplayName("Sin filtro de área el mapa solo pide alertas ACTIVA con ubicación")
        void elMapaFiltraPorEstadoActiva() {
            when(alertaRepository.findByEstadoAndUltimaUbicacionIsNotNull(EstadoAlerta.ACTIVA))
                    .thenReturn(List.of(alertaExistente(1L)));

            var resultado = alertaService.listarParaMapa(null, null, null);

            assertThat(resultado).hasSize(1);
            verify(alertaRepository).findByEstadoAndUltimaUbicacionIsNotNull(EstadoAlerta.ACTIVA);
        }

        @Test
        @DisplayName("Con lat, lng y radio el mapa se acota con la consulta espacial")
        void elMapaAcotaPorArea() {
            when(alertaRepository.buscarActivasCerca(LAT, LNG, 2000.0))
                    .thenReturn(List.of(alertaExistente(1L)));

            var resultado = alertaService.listarParaMapa(LAT, LNG, 2000.0);

            assertThat(resultado).hasSize(1);
            verify(alertaRepository).buscarActivasCerca(LAT, LNG, 2000.0);
            verify(alertaRepository, never()).findByEstadoAndUltimaUbicacionIsNotNull(any());
        }

        @Test
        @DisplayName("Un filtro de área incompleto se rechaza en vez de ignorarse")
        void elFiltroDeAreaIncompletoSeRechaza() {
            assertThatThrownBy(() -> alertaService.listarParaMapa(LAT, LNG, null))
                    .isInstanceOf(ParametrosInvalidosException.class);
            assertThatThrownBy(() -> alertaService.listarParaMapa(LAT, null, 2000.0))
                    .isInstanceOf(ParametrosInvalidosException.class);
        }

        @Test
        @DisplayName("Un radio por encima del tope se rechaza: no vale pedir el país entero")
        void elRadioTieneTope() {
            assertThatThrownBy(() -> alertaService.listarParaMapa(LAT, LNG, RADIO_MAPA_MAXIMO + 1))
                    .isInstanceOf(ParametrosInvalidosException.class);
            assertThatThrownBy(() -> alertaService.listarParaMapa(LAT, LNG, 0.0))
                    .isInstanceOf(ParametrosInvalidosException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("NaN e infinito se rechazan en los tres parámetros, en el mapa y en el listado")
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void noFinitosSeRechazan(double valor) {
            assertThatThrownBy(() -> alertaService.listarParaMapa(valor, LNG, 2000.0))
                    .isInstanceOf(ParametrosInvalidosException.class);
            assertThatThrownBy(() -> alertaService.listarParaMapa(LAT, valor, 2000.0))
                    .isInstanceOf(ParametrosInvalidosException.class);
            assertThatThrownBy(() -> alertaService.listarParaMapa(LAT, LNG, valor))
                    .isInstanceOf(ParametrosInvalidosException.class);
            assertThatThrownBy(() -> alertaService.listar(null, null, LAT, LNG, valor, PageRequest.of(0, 20)))
                    .isInstanceOf(ParametrosInvalidosException.class);
            verifyNoInteractions(alertaRepository);
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Cierre de la alerta (RF-14)")
    class Cierre {

        @Test
        @DisplayName("El autor puede cerrarla: pasa a ENCONTRADA y se avisa al mapa")
        void elAutorPuedeCerrarla() {
            Alerta alerta = alertaExistente(5L);
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));

            AlertaResponse respuesta = alertaService.cerrar(5L, 1L);

            assertThat(respuesta.getEstado()).isEqualTo(EstadoAlerta.ENCONTRADA);
            ArgumentCaptor<EventoMapaResponse> evento =
                    ArgumentCaptor.forClass(EventoMapaResponse.class);
            verify(mensajeria).convertAndSend(eq(AlertaService.CANAL_MAPA), evento.capture());
            assertThat(evento.getValue().getAccion()).isEqualTo(EventoMapaResponse.Accion.CIERRE);
        }

        @Test
        @DisplayName("Otro usuario no puede cerrar una alerta ajena")
        void otroUsuarioNoPuedeCerrarla() {
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));

            assertThatThrownBy(() -> alertaService.cerrar(5L, 999L))
                    .isInstanceOf(AccessDeniedException.class);

            verify(alertaRepository, never()).save(any());
            verify(alertaRepository, never()).bloquearParaModificar(any());
        }

        @Test
        @DisplayName("Cerrar una alerta inexistente da 404")
        void alertaInexistente() {
            when(alertaRepository.buscarAutorId(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> alertaService.cerrar(123L, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Una alerta retirada o moderada no se puede cerrar: el dueño no deshace la moderación")
        @EnumSource(value = EstadoAlerta.class, names = {"RETIRADA", "ELIMINADA"})
        void noSeCierraLoRetirado(EstadoAlerta estado) {
            Alerta alerta = alertaExistente(5L);
            alerta.setEstado(estado);
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            assertThatThrownBy(() -> alertaService.cerrar(5L, 1L))
                    .isInstanceOfSatisfying(OperacionNoPermitidaException.class,
                            e -> assertThat(e.getCodigo()).isEqualTo("ALERTA_NO_ACTIVA"));

            assertThat(alerta.getEstado()).isEqualTo(estado);
            verify(alertaRepository, never()).save(any());
            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
        }

        @Test
        @DisplayName("Cerrar otra vez una ENCONTRADA no cambia nada ni vuelve a avisar al mapa")
        void cerrarDosVecesEsIdempotente() {
            Alerta alerta = alertaExistente(5L);
            alerta.setEstado(EstadoAlerta.ENCONTRADA);
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            assertThat(alertaService.cerrar(5L, 1L).getEstado()).isEqualTo(EstadoAlerta.ENCONTRADA);

            verify(alertaRepository, never()).save(any());
            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
        }
    }

    @Nested
    @DisplayName("Edición")
    class Edicion {

        @Test
        @DisplayName("Cambiar solo la descripción no repite RF-25, y avisa al mapa con ACTUALIZACION")
        void soloDescripcion() {
            Alerta alerta = alertaExistente(5L);
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            AlertaResponse respuesta = alertaService.actualizar(5L, edicion("perro", LAT, LNG), 1L);

            assertThat(respuesta.getDescripcion()).isEqualTo("Descripción corregida");
            assertThat(alerta.getDescripcion()).isEqualTo("Descripción corregida");
            verify(alertaRepository, never()).buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong());
            ArgumentCaptor<EventoMapaResponse> evento = ArgumentCaptor.forClass(EventoMapaResponse.class);
            verify(mensajeria).convertAndSend(eq(AlertaService.CANAL_MAPA), evento.capture());
            assertThat(evento.getValue().getAccion()).isEqualTo(EventoMapaResponse.Accion.ACTUALIZACION);
        }

        @Test
        @DisplayName("Mover la ubicación repite RF-25 sobre el punto nuevo, sin contarse a sí misma")
        void moverRepiteRf25() {
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alertaExistente(5L)));
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());

            alertaService.actualizar(5L, edicion("perro", LAT + 0.05, LNG), 1L);

            verify(alertaRepository).buscarSimilar(eq("perro"), eq(LAT + 0.05), eq(LNG), eq(500.0), any(), eq(5L));
        }

        @Test
        @DisplayName("Cambiar la especie a la de una alerta vecina es 409, y la alerta no cambia")
        void cambiarEspecieADuplicada() {
            Alerta alerta = alertaExistente(5L);
            alerta.setDescripcion("Original");
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.of(alertaExistente(42L)));

            assertThatThrownBy(() -> alertaService.actualizar(5L, edicion("gato", LAT, LNG), 1L))
                    .isInstanceOf(AlertaDuplicadaException.class);

            assertThat(alerta.getEspecie()).isEqualTo("perro");
            assertThat(alerta.getDescripcion()).isEqualTo("Original");
            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
        }

        @Test
        @DisplayName("Cambiar solo mayúsculas de la especie no es cambiar de especie")
        void especieEnMayusculas() {
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alertaExistente(5L)));

            alertaService.actualizar(5L, edicion("PERRO", LAT, LNG), 1L);

            verify(alertaRepository, never()).buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong());
        }

        @Test
        @DisplayName("Una foto nueva tiene que ser del autor; la que ya tenía no se vuelve a comprobar")
        void foto() {
            Alerta alerta = alertaExistente(5L);
            alerta.setFotoUrl("https://fotos.test/fotos/vieja.jpg");
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            ActualizarAlertaRequest misma = edicion("perro", LAT, LNG);
            misma.setFotoUrl("https://fotos.test/fotos/vieja.jpg");
            alertaService.actualizar(5L, misma, 1L);
            verify(fotoService, never()).exigirPropia(any(), any());

            ActualizarAlertaRequest nueva = edicion("perro", LAT, LNG);
            nueva.setFotoUrl("https://fotos.test/fotos/nueva.jpg");
            alertaService.actualizar(5L, nueva, 1L);
            verify(fotoService).exigirPropia("https://fotos.test/fotos/nueva.jpg", 1L);
        }

        @Test
        @DisplayName("Otro usuario no puede editarla")
        void ajena() {
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));

            assertThatThrownBy(() -> alertaService.actualizar(5L, edicion("perro", LAT, LNG), 999L))
                    .isInstanceOf(AccessDeniedException.class);
            verify(alertaRepository, never()).bloquearParaModificar(any());
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Una alerta que ya no está ACTIVA no se edita")
        @EnumSource(value = EstadoAlerta.class, names = {"ENCONTRADA", "RETIRADA", "ELIMINADA"})
        void noActiva(EstadoAlerta estado) {
            Alerta alerta = alertaExistente(5L);
            alerta.setDescripcion("Original");
            alerta.setEstado(estado);
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            assertThatThrownBy(() -> alertaService.actualizar(5L, edicion("perro", LAT, LNG), 1L))
                    .isInstanceOfSatisfying(OperacionNoPermitidaException.class,
                            e -> assertThat(e.getCodigo()).isEqualTo("ALERTA_NO_ACTIVA"));
            assertThat(alerta.getDescripcion()).isEqualTo("Original");
        }

        @Test
        @DisplayName("Una alerta inexistente da 404")
        void inexistente() {
            when(alertaRepository.buscarAutorId(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> alertaService.actualizar(123L, edicion("perro", LAT, LNG), 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        private ActualizarAlertaRequest edicion(String especie, double latitud, double longitud) {
            return new ActualizarAlertaRequest("Descripción corregida", null, especie,
                    Instant.now().minus(1, ChronoUnit.HOURS), new UbicacionRequest(latitud, longitud), 1000);
        }
    }

    @Nested
    @DisplayName("Retirada por el dueño")
    class RetiradaPorElDuenyo {

        @Test
        @DisplayName("Una ACTIVA pasa a RETIRADA, sin borrarse, y su marcador sale del mapa")
        void activa() {
            Alerta alerta = alertaExistente(5L);
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            alertaService.retirar(5L, 1L);

            assertThat(alerta.getEstado()).isEqualTo(EstadoAlerta.RETIRADA);
            verify(alertaRepository, never()).delete(any(Alerta.class));
            verify(alertaRepository, never()).deleteById(any());
            ArgumentCaptor<EventoMapaResponse> evento = ArgumentCaptor.forClass(EventoMapaResponse.class);
            verify(mensajeria).convertAndSend(eq(AlertaService.CANAL_MAPA), evento.capture());
            assertThat(evento.getValue().getAccion()).isEqualTo(EventoMapaResponse.Accion.CIERRE);
        }

        @Test
        @DisplayName("Una ENCONTRADA también se retira, sin evento: ya no estaba en el mapa")
        void encontrada() {
            Alerta alerta = alertaExistente(5L);
            alerta.setEstado(EstadoAlerta.ENCONTRADA);
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            alertaService.retirar(5L, 1L);

            assertThat(alerta.getEstado()).isEqualTo(EstadoAlerta.RETIRADA);
            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Sobre una RETIRADA o ELIMINADA no cambia nada: el dueño no pisa la moderación")
        @EnumSource(value = EstadoAlerta.class, names = {"RETIRADA", "ELIMINADA"})
        void yaRetirada(EstadoAlerta estado) {
            Alerta alerta = alertaExistente(5L);
            alerta.setEstado(estado);
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            alertaService.retirar(5L, 1L);

            assertThat(alerta.getEstado()).isEqualTo(estado);
            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
        }

        @Test
        @DisplayName("Otro usuario no puede retirarla")
        void ajena() {
            when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));

            assertThatThrownBy(() -> alertaService.retirar(5L, 999L)).isInstanceOf(AccessDeniedException.class);
            verify(alertaRepository, never()).bloquearParaModificar(any());
        }

        @Test
        @DisplayName("Una alerta inexistente da 404")
        void inexistente() {
            when(alertaRepository.buscarAutorId(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> alertaService.retirar(123L, 1L)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Detalle")
    class Detalle {

        @ParameterizedTest(name = "{0}")
        @DisplayName("Una ACTIVA o ENCONTRADA se ve entera, con sus avistamientos")
        @EnumSource(value = EstadoAlerta.class, names = {"ACTIVA", "ENCONTRADA"})
        void completa(EstadoAlerta estado) {
            Alerta alerta = alertaExistente(5L);
            alerta.setDescripcion("Perro pequeño marrón");
            alerta.setEstado(estado);
            when(alertaRepository.findById(5L)).thenReturn(Optional.of(alerta));
            AvistamientoResponse visto = AvistamientoResponse.builder().id(70L).alertaId(5L).build();
            when(avistamientoService.visiblesDeLaAlerta(5L)).thenReturn(List.of(visto));

            AlertaDetalleResponse detalle = alertaService.detalle(5L);

            assertThat(detalle.getEstado()).isEqualTo(estado);
            assertThat(detalle.getDescripcion()).isEqualTo("Perro pequeño marrón");
            assertThat(detalle.getUbicacion()).isNotNull();
            assertThat(detalle.getAutor().getNombre()).isEqualTo("Ana");
            assertThat(detalle.getAvistamientos()).containsExactly(visto);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Una RETIRADA o ELIMINADA responde con su estado, pero sin su contenido")
        @EnumSource(value = EstadoAlerta.class, names = {"RETIRADA", "ELIMINADA"})
        void reducida(EstadoAlerta estado) {
            Alerta alerta = alertaExistente(5L);
            alerta.setDescripcion("Contenido que se retiró");
            alerta.setFotoUrl("https://fotos.test/fotos/x.jpg");
            alerta.setEstado(estado);
            when(alertaRepository.findById(5L)).thenReturn(Optional.of(alerta));

            AlertaDetalleResponse detalle = alertaService.detalle(5L);

            assertThat(detalle.getId()).isEqualTo(5L);
            assertThat(detalle.getEstado()).isEqualTo(estado);
            assertThat(detalle.getEspecie()).isEqualTo("perro");
            assertThat(detalle).extracting("descripcion", "fotoUrl", "ubicacion", "autor", "fechaAvistamiento")
                    .containsOnlyNulls();
            assertThat(detalle.getAvistamientos()).isEmpty();
            verify(avistamientoService, never()).visiblesDeLaAlerta(anyLong());
        }

        /*
         * Se serializa la respuesta que produce el service a partir de un autor
         * que sí tiene email y teléfono. Comprobarlo en el controller con un
         * service simulado no prueba nada: la respuesta simulada ya viene sin
         * ellos.
         */
        @Test
        @DisplayName("Ni el detalle ni el listado llevan el email ni el teléfono del autor (RNF-10)")
        void sinContactoDelAutor() {
            Alerta alerta = alertaExistente(5L);
            alerta.getUsuario().setTelefono("600111222");
            when(alertaRepository.findById(5L)).thenReturn(Optional.of(alerta));
            when(avistamientoService.visiblesDeLaAlerta(5L)).thenReturn(List.of());
            when(alertaRepository.findAll(ArgumentMatchers.<Specification<Alerta>>any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(alerta)));

            String detalle = JsonMapper.builder().build().writeValueAsString(alertaService.detalle(5L));
            String listado = JsonMapper.builder().build().writeValueAsString(
                    alertaService.listar(null, null, null, null, null, PageRequest.of(0, 20)));

            assertThat(detalle).contains("\"nombre\":\"Ana\"").doesNotContain("ana@test.es", "600111222");
            assertThat(listado).contains("\"nombre\":\"Ana\"").doesNotContain("ana@test.es", "600111222");
        }

        @Test
        @DisplayName("Publicar tampoco devuelve el email ni el teléfono del autor (RNF-10)")
        void publicarSinContactoDelAutor() {
            Usuario autor = autor();
            autor.setTelefono("600111222");
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));

            String publicada = JsonMapper.builder().build().writeValueAsString(alertaService.publicar(peticion(), 1L));

            assertThat(publicada).contains("\"nombre\":\"Ana\"").doesNotContain("ana@test.es", "600111222");
        }

        @Test
        @DisplayName("Solo un id que nunca existió da 404")
        void inexistente() {
            when(alertaRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> alertaService.detalle(123L)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Retirada por moderación (RF-11)")
    class Moderacion {

        @Test
        @DisplayName("Una ACTIVA pasa a ELIMINADA, sin borrarse, y su marcador sale del mapa")
        void activaSaleDelMapa() {
            Alerta alerta = alertaExistente(5L);
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            assertThat(alertaService.retirarPorModeracion(5L, 99L)).isTrue();

            assertThat(alerta.getEstado()).isEqualTo(EstadoAlerta.ELIMINADA);
            verify(alertaRepository, never()).delete(any(Alerta.class));
            verify(alertaRepository, never()).deleteById(any());
            ArgumentCaptor<EventoMapaResponse> evento = ArgumentCaptor.forClass(EventoMapaResponse.class);
            verify(mensajeria).convertAndSend(eq(AlertaService.CANAL_MAPA), evento.capture());
            assertThat(evento.getValue().getAccion()).isEqualTo(EventoMapaResponse.Accion.CIERRE);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Una ENCONTRADA o RETIRADA también pasa a ELIMINADA, sin evento: ya no estaba en el mapa")
        @EnumSource(value = EstadoAlerta.class, names = {"ENCONTRADA", "RETIRADA"})
        void terminadaPasaAEliminada(EstadoAlerta estado) {
            Alerta alerta = alertaExistente(5L);
            alerta.setEstado(estado);
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            assertThat(alertaService.retirarPorModeracion(5L, 99L)).isTrue();

            assertThat(alerta.getEstado()).isEqualTo(EstadoAlerta.ELIMINADA);
            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
        }

        @Test
        @DisplayName("Retirar otra vez una ELIMINADA no cambia nada")
        void idempotente() {
            Alerta alerta = alertaExistente(5L);
            alerta.setEstado(EstadoAlerta.ELIMINADA);
            alerta.setFotoUrl("https://fotos.test/fotos/x.jpg");
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            assertThat(alertaService.retirarPorModeracion(5L, 99L)).isFalse();

            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
            verify(fotoService, never()).retirarDeAlertaModerada(any(), any(), any());
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Al moderarla, la alerta deja de apuntar a su foto y se pide borrarla, venga del estado que venga")
        @EnumSource(value = EstadoAlerta.class, names = {"ACTIVA", "ENCONTRADA", "RETIRADA"})
        void borraLaFoto(EstadoAlerta estado) {
            Alerta alerta = alertaExistente(5L);
            alerta.setEstado(estado);
            alerta.setFotoUrl("https://fotos.test/fotos/x.jpg");
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alerta));

            alertaService.retirarPorModeracion(5L, 99L);

            assertThat(alerta.getFotoUrl()).isNull();
            verify(fotoService).retirarDeAlertaModerada("https://fotos.test/fotos/x.jpg", 1L, 5L);
        }

        @Test
        @DisplayName("Sin foto no hay nada que borrar")
        void sinFoto() {
            when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alertaExistente(5L)));

            alertaService.retirarPorModeracion(5L, 99L);

            verify(fotoService, never()).retirarDeAlertaModerada(any(), any(), any());
        }

        @Test
        @DisplayName("Una alerta inexistente da 404")
        void inexistente() {
            when(alertaRepository.bloquearParaModificar(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> alertaService.retirarPorModeracion(123L, 99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("La foto tiene que ser del autor")
    class FotoDelAutor {

        private static final String URL = "https://fotos.test/fotos/3f1c2a9e-0b7d-4c55-9a8e-6d2f1b0c7e4a.jpg";

        @Test
        @DisplayName("Con una foto ajena no se publica ni se busca duplicado")
        void fotoAjena() {
            CrearAlertaRequest conFoto = peticion();
            conFoto.setFotoUrl(URL);
            doThrow(new FotoUrlNoValidaException("ajena")).when(fotoService).exigirPropia(URL, 1L);

            assertThatThrownBy(() -> alertaService.publicar(conFoto, 1L))
                    .isInstanceOf(FotoUrlNoValidaException.class);

            verify(alertaRepository, never()).buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong());
            verify(alertaRepository, never()).save(any());
        }

        @Test
        @DisplayName("Con una foto propia se comprueba recortada y se guarda")
        void fotoPropia() {
            prepararPublicacion();
            CrearAlertaRequest conFoto = peticion();
            conFoto.setFotoUrl("  " + URL + " ");

            assertThat(alertaService.publicar(conFoto, 1L).getFotoUrl()).isEqualTo(URL);
            verify(fotoService).exigirPropia(URL, 1L);
        }

        @Test
        @DisplayName("Una foto en blanco es no mandar foto: ni se comprueba ni se guarda la cadena")
        void fotoEnBlanco() {
            prepararPublicacion();
            CrearAlertaRequest sinFoto = peticion();
            sinFoto.setFotoUrl("   ");

            assertThat(alertaService.publicar(sinFoto, 1L).getFotoUrl()).isNull();
            verify(fotoService, never()).exigirPropia(any(), any());
        }

        private void prepararPublicacion() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(), anyLong()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));
        }
    }

    @Nested
    @DisplayName("Cuándo sale el evento del mapa: tras el commit, y nunca si hay rollback")
    class MomentoDelEvento {

        /*
         * Las demás pruebas corren sin transacción, y entonces publicarEnMapa
         * envía en el acto: prueban qué se emite, no cuándo. Aquí se abre la
         * sincronización de transacciones como lo hace Spring al entrar en un
         * método @Transactional, y se dispara a mano el final de la transacción.
         */
        @BeforeEach
        void abrirTransaccion() {
            TransactionSynchronizationManager.initSynchronization();
        }

        @AfterEach
        void cerrarTransaccion() {
            TransactionSynchronizationManager.clearSynchronization();
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Nada se envía dentro de la transacción; tras el commit, una vez")
        @ValueSource(strings = {"publicar", "editar", "retirar", "cerrar", "moderar"})
        void soloTrasElCommit(String operacion) {
            ejecutar(operacion);
            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(mensajeria).convertAndSend(eq(AlertaService.CANAL_MAPA), any(EventoMapaResponse.class));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Si la transacción se deshace, no se envía nada")
        @ValueSource(strings = {"publicar", "editar", "retirar", "cerrar", "moderar"})
        void nadaTrasUnRollback(String operacion) {
            ejecutar(operacion);

            TransactionSynchronizationUtils.invokeAfterCompletion(
                    TransactionSynchronizationManager.getSynchronizations(),
                    TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(mensajeria, never()).convertAndSend(anyString(), any(EventoMapaResponse.class));
        }

        /** Cada operación que avisa al mapa, sobre una alerta ACTIVA de su autor. */
        private void ejecutar(String operacion) {
            switch (operacion) {
                case "publicar" -> {
                    when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any(),
                            anyLong())).thenReturn(Optional.empty());
                    when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
                    when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));
                    alertaService.publicar(peticion(), 1L);
                }
                case "editar" -> {
                    when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
                    when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alertaExistente(5L)));
                    alertaService.actualizar(5L, new ActualizarAlertaRequest("Otra descripción", null, "perro",
                            Instant.now(), new UbicacionRequest(LAT, LNG), null), 1L);
                }
                case "retirar" -> {
                    when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
                    when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alertaExistente(5L)));
                    alertaService.retirar(5L, 1L);
                }
                case "cerrar" -> {
                    when(alertaRepository.buscarAutorId(5L)).thenReturn(Optional.of(1L));
                    when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alertaExistente(5L)));
                    when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));
                    alertaService.cerrar(5L, 1L);
                }
                case "moderar" -> {
                    when(alertaRepository.bloquearParaModificar(5L)).thenReturn(Optional.of(alertaExistente(5L)));
                    alertaService.retirarPorModeracion(5L, 2L);
                }
                default -> throw new IllegalArgumentException(operacion);
            }
        }
    }

    // ---------------------------------------------------------------------

    private CrearAlertaRequest peticion() {
        return new CrearAlertaRequest("Perro pequeño marrón", null, "perro",
                Instant.now().minus(1, ChronoUnit.HOURS), new UbicacionRequest(LAT, LNG), 1000);
    }

    private Usuario autor() {
        Usuario usuario = new Usuario();
        usuario.setId(1L);
        usuario.setNombre("Ana");
        usuario.setEmail("ana@test.es");
        usuario.setAreaGeografica("Madrid");
        usuario.setRol(Rol.CIUDADANO);
        usuario.setActivo(true);
        return usuario;
    }

    private Alerta alertaExistente(Long id) {
        Alerta alerta = new Alerta();
        alerta.setId(id);
        alerta.setUsuario(autor());
        alerta.setEspecie("perro");
        alerta.setEstado(EstadoAlerta.ACTIVA);
        alerta.setFechaAvistamiento(Instant.now().minus(2, ChronoUnit.HOURS));
        GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 4326);
        alerta.setUltimaUbicacion(factory.createPoint(new Coordinate(LNG, LAT)));
        return alerta;
    }
}
