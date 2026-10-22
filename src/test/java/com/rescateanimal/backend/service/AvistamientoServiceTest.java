package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.AvistamientoFeedResponse;
import com.rescateanimal.backend.dto.response.AvistamientoResponse;
import com.rescateanimal.backend.exception.FotoUrlNoValidaException;
import com.rescateanimal.backend.exception.LimiteAvistamientosAlcanzadoException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.LimiteAvistamientoDiarioRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas del registro de avistamientos y del feed de swipe (RF-15, RF-21).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvistamientoServiceTest {

    private static final int TAMANYO_FEED = 30;
    private static final int LIMITE_POR_ALERTA = 5;
    /** El día del cupo es el de Madrid; ver AvistamientoService.ZONA_CUPO. */
    private static final ZoneId ZONA_CUPO = ZoneId.of("Europe/Madrid");
    private static final Long ID_USUARIO = 7L;
    private static final Long ID_ALERTA = 55L;
    private static final double LAT = 40.4168;
    private static final double LNG = -3.7038;

    @Mock
    private FotoService fotoService;
    @Mock
    private AvistamientoRepository avistamientoRepository;
    @Mock
    private AlertaRepository alertaRepository;
    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private NotificacionService notificacionService;
    @Mock
    private LimiteAvistamientoDiarioRepository limiteAvistamientoDiarioRepository;

    private AvistamientoService avistamientoService;

    @BeforeEach
    void prepararServicio() {
        avistamientoService = new AvistamientoService(
                avistamientoRepository, alertaRepository, usuarioRepository, fotoService,
                notificacionService, limiteAvistamientoDiarioRepository, TAMANYO_FEED, LIMITE_POR_ALERTA);

        // Por defecto queda cupo.
        when(limiteAvistamientoDiarioRepository.incrementarSiHayCupo(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(1);
        when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario(ID_USUARIO)));
        when(avistamientoRepository.save(any(Avistamiento.class))).thenAnswer(i -> {
            Avistamiento a = i.getArgument(0);
            a.setId(77L);
            return a;
        });
    }

    @Nested
    @DisplayName("Registro (RF-21)")
    class Registro {

        @Test
        @DisplayName("Sobre una alerta ACTIVA el avistamiento se registra")
        void seRegistraSobreAlertaActiva() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));

            AvistamientoResponse respuesta = avistamientoService.registrar(peticion(), ID_USUARIO);

            assertThat(respuesta.getId()).isEqualTo(77L);
            assertThat(respuesta.getAlertaId()).isEqualTo(ID_ALERTA);
            assertThat(respuesta.getUbicacion().getLatitud()).isEqualTo(LAT);
            assertThat(respuesta.getUbicacion().getLongitud()).isEqualTo(LNG);
        }

        @Test
        @DisplayName("Se avisa al dueño de la alerta, con la alerta como referencia")
        void seAvisaAlDueno() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));

            avistamientoService.registrar(peticion(), ID_USUARIO);

            // alerta() es de 99: el dueño, distinto del autor del avistamiento.
            verify(notificacionService).avisarNuevoAvistamiento(ID_ALERTA, 99L, ID_USUARIO);
        }

        @Test
        @DisplayName("Un avistamiento rechazado no avisa a nadie")
        void unRechazoNoAvisa() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ENCONTRADA)));

            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(OperacionNoPermitidaException.class);

            verify(notificacionService, never()).avisarNuevoAvistamiento(any(), any(), any());
        }

        @Test
        @DisplayName("La respuesta del autor no expone email ni teléfono (RNF-10)")
        void elAutorNoExponeDatosDeContacto() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));

            AvistamientoResponse respuesta = avistamientoService.registrar(peticion(), ID_USUARIO);

            assertThat(respuesta.getAutor().getId()).isEqualTo(ID_USUARIO);
            assertThat(respuesta.getAutor().getNombre()).isEqualTo("Usuario " + ID_USUARIO);
        }

        @Test
        @DisplayName("La ubicación se guarda como Point con el orden (longitud, latitud) de JTS")
        void laUbicacionSeGuardaComoPoint() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));

            avistamientoService.registrar(peticion(), ID_USUARIO);

            ArgumentCaptor<Avistamiento> guardado = ArgumentCaptor.forClass(Avistamiento.class);
            verify(avistamientoRepository).save(guardado.capture());

            Point punto = guardado.getValue().getUbicacion();
            assertThat(punto.getX()).isEqualTo(LNG);
            assertThat(punto.getY()).isEqualTo(LAT);
            assertThat(punto.getSRID()).isEqualTo(4326);
        }

        @Test
        @DisplayName("Con una foto que no subió el autor no se registra nada")
        void fotoAjenaSeRechaza() {
            doThrow(new FotoUrlNoValidaException("ajena"))
                    .when(fotoService).exigirPropia("https://fotos/avistamiento.jpg", ID_USUARIO);

            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(FotoUrlNoValidaException.class);

            verify(avistamientoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Si la alerta no existe se rechaza con 404")
        void alertaInexistenteSeRechaza() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(avistamientoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Sobre una alerta ya cerrada no se admiten avistamientos")
        void alertaEncontradaSeRechaza() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ENCONTRADA)));

            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(OperacionNoPermitidaException.class)
                    .satisfies(e -> assertThat(((OperacionNoPermitidaException) e).getCodigo())
                            .isEqualTo("ALERTA_NO_ACTIVA"));

            verify(avistamientoRepository, never()).save(any());
        }

        @Test
        @DisplayName("Sobre una alerta eliminada tampoco")
        void alertaEliminadaSeRechaza() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ELIMINADA)));

            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(OperacionNoPermitidaException.class);
        }
    }

    /**
     * Cupo diario por usuario y alerta. Las consultas nativas del contador se
     * simulan aquí; que su SQL sea válido, y atómico con dos a la vez, lo
     * comprueban MatchFlujoCompletoTest y AlertaBloqueoTest contra PostgreSQL.
     */
    @Nested
    @DisplayName("Cupo diario por alerta")
    class Cupo {

        @Test
        @DisplayName("Cada avistamiento consume el cupo del autor en esa alerta, del día de Madrid, antes del INSERT")
        void consumeElCupoAntesDeGuardar() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));

            avistamientoService.registrar(peticion(), ID_USUARIO);

            LocalDate hoy = LocalDate.now(ZONA_CUPO);
            InOrder orden = inOrder(limiteAvistamientoDiarioRepository, avistamientoRepository);
            orden.verify(limiteAvistamientoDiarioRepository).crearContadorSiNoExiste(ID_USUARIO, ID_ALERTA, hoy);
            orden.verify(limiteAvistamientoDiarioRepository)
                    .incrementarSiHayCupo(ID_USUARIO, ID_ALERTA, hoy, LIMITE_POR_ALERTA);
            orden.verify(avistamientoRepository).save(any());
        }

        @Test
        @DisplayName("Sin cupo -> 429 con el límite y la hora de reinicio, sin registro ni aviso al dueño")
        void sinCupoSeRechaza() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));
            when(limiteAvistamientoDiarioRepository.incrementarSiHayCupo(anyLong(), anyLong(), any(), anyInt()))
                    .thenReturn(0);

            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(LimiteAvistamientosAlcanzadoException.class)
                    .satisfies(e -> {
                        LimiteAvistamientosAlcanzadoException ex = (LimiteAvistamientosAlcanzadoException) e;
                        assertThat(ex.getLimiteDiario()).isEqualTo(LIMITE_POR_ALERTA);
                        assertThat(ex.getReiniciaEn())
                                .isEqualTo(LocalDate.now(ZONA_CUPO).plusDays(1).atStartOfDay());
                        assertThat(ex.getMessage()).contains("esta alerta", "mañana");
                    });

            verify(avistamientoRepository, never()).save(any());
            verify(notificacionService, never()).avisarNuevoAvistamiento(any(), any(), any());
        }

        @Test
        @DisplayName("Una alerta inexistente o ya cerrada no consume cupo: se comprueba antes")
        void laAlertaSeCompruebaAntes() {
            when(alertaRepository.bloquearParaAvistar(ID_ALERTA)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(ResourceNotFoundException.class);

            when(alertaRepository.bloquearParaAvistar(ID_ALERTA))
                    .thenReturn(Optional.of(alerta(EstadoAlerta.ENCONTRADA)));
            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(OperacionNoPermitidaException.class);

            verifyNoInteractions(limiteAvistamientoDiarioRepository);
        }

        @Test
        @DisplayName("Una foto ajena tampoco lo consume")
        void laFotoSeCompruebaAntes() {
            doThrow(new FotoUrlNoValidaException("ajena"))
                    .when(fotoService).exigirPropia("https://fotos/avistamiento.jpg", ID_USUARIO);

            assertThatThrownBy(() -> avistamientoService.registrar(peticion(), ID_USUARIO))
                    .isInstanceOf(FotoUrlNoValidaException.class);

            verifyNoInteractions(limiteAvistamientoDiarioRepository);
        }
    }

    @Nested
    @DisplayName("Feed de una alerta, para su dueño")
    class FeedDeLaAlerta {

        private static final Long ID_DUENO = 99L;

        @Test
        @DisplayName("Cada tarjeta lleva también la especie y la foto de la alerta, para poder comparar")
        void cadaTarjetaLlevaLaAlerta() {
            when(alertaRepository.findById(ID_ALERTA)).thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));
            Avistamiento candidato = avistamientoDe(alerta(EstadoAlerta.ACTIVA));
            when(avistamientoRepository.buscarPendientesDeRevisar(any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(List.of(candidato));

            List<AvistamientoFeedResponse> feed = avistamientoService.feedDeLaAlerta(ID_ALERTA, ID_DUENO);

            assertThat(feed).hasSize(1);
            assertThat(feed.get(0).getAlertaId()).isEqualTo(ID_ALERTA);
            assertThat(feed.get(0).getAlertaEspecie()).isEqualTo("perro");
            assertThat(feed.get(0).getAlertaFotoUrl()).isEqualTo("https://fotos/alerta.jpg");
            assertThat(feed.get(0).getFotoUrl()).isEqualTo("https://fotos/avistamiento.jpg");
        }

        @Test
        @DisplayName("Pide los VISIBLE de esa alerta, sin lo que el dueño ya revisó, en tandas acotadas")
        void pideLosFiltrosCorrectos() {
            when(alertaRepository.findById(ID_ALERTA)).thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));

            avistamientoService.feedDeLaAlerta(ID_ALERTA, ID_DUENO);

            verify(avistamientoRepository).buscarPendientesDeRevisar(
                    ID_ALERTA, ID_DUENO, EstadoAvistamiento.VISIBLE, TipoPublicacion.AVISTAMIENTO,
                    PageRequest.of(0, TAMANYO_FEED));
        }

        @Test
        @DisplayName("Cada tarjeta lleva su distancia a la alerta en metros redondeados, y null si no hay")
        void distancia() {
            when(alertaRepository.findById(ID_ALERTA)).thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));
            Avistamiento conUbicacion = avistamientoDe(alerta(EstadoAlerta.ACTIVA));
            Avistamiento sinUbicacion = avistamientoDe(alerta(EstadoAlerta.ACTIVA));
            sinUbicacion.setId(78L);
            sinUbicacion.setUbicacion(null);
            when(avistamientoRepository.buscarPendientesDeRevisar(any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(List.of(conUbicacion, sinUbicacion));
            when(avistamientoRepository.distanciasALaAlerta(List.of(77L, 78L)))
                    .thenReturn(List.of(filaDeDistancia(77L, 299.6)));

            List<AvistamientoFeedResponse> feed = avistamientoService.feedDeLaAlerta(ID_ALERTA, ID_DUENO);

            assertThat(feed).extracting(AvistamientoFeedResponse::getId).containsExactly(77L, 78L);
            assertThat(feed.get(0).getDistanciaMetros()).isEqualTo(300L);
            assertThat(feed.get(1).getDistanciaMetros()).isNull();
        }

        @Test
        @DisplayName("Sin tarjetas no se consultan distancias")
        void sinTarjetasNoHayConsultaDeDistancias() {
            when(alertaRepository.findById(ID_ALERTA)).thenReturn(Optional.of(alerta(EstadoAlerta.ACTIVA)));

            assertThat(avistamientoService.feedDeLaAlerta(ID_ALERTA, ID_DUENO)).isEmpty();

            verify(avistamientoRepository, never()).distanciasALaAlerta(any());
        }

        @Test
        @DisplayName("Alerta inexistente -> 404")
        void alertaInexistente() {
            when(alertaRepository.findById(ID_ALERTA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> avistamientoService.feedDeLaAlerta(ID_ALERTA, ID_DUENO))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Alerta ajena -> 403, también si además está cerrada: el permiso va antes que el estado")
        void alertaAjena() {
            when(alertaRepository.findById(ID_ALERTA)).thenReturn(Optional.of(alerta(EstadoAlerta.ENCONTRADA)));

            assertThatThrownBy(() -> avistamientoService.feedDeLaAlerta(ID_ALERTA, ID_USUARIO))
                    .isInstanceOf(AccessDeniedException.class);

            verify(avistamientoRepository, never()).buscarPendientesDeRevisar(any(), any(), any(), any(), any());
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("Alerta propia que ya no está ACTIVA -> 400 ALERTA_NO_ACTIVA")
        @EnumSource(value = EstadoAlerta.class, names = {"ENCONTRADA", "RETIRADA", "ELIMINADA"})
        void alertaNoActiva(EstadoAlerta estado) {
            when(alertaRepository.findById(ID_ALERTA)).thenReturn(Optional.of(alerta(estado)));

            assertThatThrownBy(() -> avistamientoService.feedDeLaAlerta(ID_ALERTA, ID_DUENO))
                    .isInstanceOf(OperacionNoPermitidaException.class)
                    .satisfies(e -> assertThat(((OperacionNoPermitidaException) e).getCodigo())
                            .isEqualTo("ALERTA_NO_ACTIVA"));
        }
    }

    // ---------------------------------------------------------------------

    private static AvistamientoRepository.DistanciaALaAlerta filaDeDistancia(Long avistamientoId, double metros) {
        return new AvistamientoRepository.DistanciaALaAlerta() {
            @Override
            public Long getAvistamientoId() {
                return avistamientoId;
            }

            @Override
            public Double getMetros() {
                return metros;
            }
        };
    }

    private static CrearAvistamientoRequest peticion() {
        return new CrearAvistamientoRequest(ID_ALERTA, "Lo he visto en el parque",
                "https://fotos/avistamiento.jpg", new UbicacionRequest(LAT, LNG));
    }

    private static Alerta alerta(EstadoAlerta estado) {
        Alerta alerta = new Alerta();
        alerta.setId(ID_ALERTA);
        alerta.setEstado(estado);
        alerta.setEspecie("perro");
        alerta.setFotoUrl("https://fotos/alerta.jpg");
        alerta.setUsuario(usuario(99L));
        return alerta;
    }

    private static Avistamiento avistamientoDe(Alerta alerta) {
        Avistamiento avistamiento = new Avistamiento();
        avistamiento.setId(77L);
        avistamiento.setAlerta(alerta);
        avistamiento.setUsuario(usuario(8L));
        avistamiento.setDescripcion("Lo he visto en el parque");
        avistamiento.setFotoUrl("https://fotos/avistamiento.jpg");
        avistamiento.setUbicacion(punto());
        avistamiento.setEstado(EstadoAvistamiento.VISIBLE);
        return avistamiento;
    }

    private static Point punto() {
        Point punto = new GeometryFactory(new PrecisionModel(), 4326)
                .createPoint(new Coordinate(LNG, LAT));
        punto.setSRID(4326);
        return punto;
    }

    private static Usuario usuario(Long id) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setNombre("Usuario " + id);
        usuario.setEmail("usuario" + id + "@test.es");
        usuario.setRol(Rol.CIUDADANO);
        usuario.setActivo(true);
        return usuario;
    }
}
