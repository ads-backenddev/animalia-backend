package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearAlertaRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.AlertaResponse;
import com.rescateanimal.backend.dto.response.EventoMapaResponse;
import com.rescateanimal.backend.exception.AlertaDuplicadaException;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
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
                notificacionService, mensajeria, RADIO_METROS, VENTANA_HORAS, RADIO_MAPA_MAXIMO);
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Detección de alertas duplicadas (RF-25)")
    class Duplicados {

        @Test
        @DisplayName("Sin coincidencias la alerta se publica")
        void publicaCuandoNoHayDuplicado() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
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
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
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
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
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
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
                    .thenReturn(Optional.empty());
            when(usuarioRepository.findById(1L)).thenReturn(Optional.of(autor()));
            when(alertaRepository.save(any(Alerta.class))).thenAnswer(i -> i.getArgument(0));
            LocalDateTime antesDeLlamar = LocalDateTime.now().minusHours(VENTANA_HORAS);

            alertaService.publicar(peticion(), 1L);

            ArgumentCaptor<Double> radio = ArgumentCaptor.forClass(Double.class);
            ArgumentCaptor<LocalDateTime> desde = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(alertaRepository).buscarSimilar(eq("perro"), eq(LAT), eq(LNG),
                    radio.capture(), desde.capture());

            assertThat(radio.getValue()).isEqualTo(500.0);
            assertThat(desde.getValue()).isBetween(antesDeLlamar.minusMinutes(1),
                    antesDeLlamar.plusMinutes(1));
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Geometría")
    class Geometria {

        @Test
        @DisplayName("La latitud y la longitud se guardan en el orden que espera JTS: x=longitud, y=latitud")
        void guardaElPuntoConElOrdenCorrecto() {
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
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
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
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
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
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
            when(alertaRepository.buscarSimilar(anyString(), anyDouble(), anyDouble(), anyDouble(), any()))
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
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Cierre de la alerta (RF-14)")
    class Cierre {

        @Test
        @DisplayName("El autor puede cerrarla: pasa a ENCONTRADA y se avisa al mapa")
        void elAutorPuedeCerrarla() {
            Alerta alerta = alertaExistente(5L);
            when(alertaRepository.findById(5L)).thenReturn(Optional.of(alerta));
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
            when(alertaRepository.findById(5L)).thenReturn(Optional.of(alertaExistente(5L)));

            assertThatThrownBy(() -> alertaService.cerrar(5L, 999L))
                    .isInstanceOf(AccessDeniedException.class);

            verify(alertaRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cerrar una alerta inexistente da 404")
        void alertaInexistente() {
            when(alertaRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> alertaService.cerrar(123L, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ---------------------------------------------------------------------

    private CrearAlertaRequest peticion() {
        return new CrearAlertaRequest("Perro pequeño marrón", null, "perro",
                LocalDateTime.now().minusHours(1), new UbicacionRequest(LAT, LNG), 1000);
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
        alerta.setFechaAvistamiento(LocalDateTime.now().minusHours(2));
        GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 4326);
        alerta.setUltimaUbicacion(factory.createPoint(new Coordinate(LNG, LAT)));
        return alerta;
    }
}
