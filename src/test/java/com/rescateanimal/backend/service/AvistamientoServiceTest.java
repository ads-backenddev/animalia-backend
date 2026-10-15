package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearAvistamientoRequest;
import com.rescateanimal.backend.dto.request.UbicacionRequest;
import com.rescateanimal.backend.dto.response.AvistamientoFeedResponse;
import com.rescateanimal.backend.dto.response.AvistamientoResponse;
import com.rescateanimal.backend.exception.FotoUrlNoValidaException;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del registro de avistamientos y del feed de swipe (RF-15, RF-21).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvistamientoServiceTest {

    private static final int TAMANYO_FEED = 30;
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

    private AvistamientoService avistamientoService;

    @BeforeEach
    void prepararServicio() {
        avistamientoService = new AvistamientoService(
                avistamientoRepository, alertaRepository, usuarioRepository, fotoService, TAMANYO_FEED);

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

    @Nested
    @DisplayName("Feed de swipe (RF-15)")
    class Feed {

        @Test
        @DisplayName("El feed pide candidatos ACTIVA, de tipo AVISTAMIENTO y acotados en tamaño")
        void elFeedPideLosFiltrosCorrectos() {
            when(avistamientoRepository.buscarCandidatosParaFeed(
                    any(), any(), any(), any(), any(Pageable.class))).thenReturn(List.of());

            avistamientoService.feed(ID_USUARIO);

            verify(avistamientoRepository).buscarCandidatosParaFeed(
                    ID_USUARIO,
                    // Los retirados por el administrador (RF-11) no vuelven al feed.
                    EstadoAvistamiento.VISIBLE,
                    EstadoAlerta.ACTIVA,
                    TipoPublicacion.AVISTAMIENTO,
                    PageRequest.of(0, TAMANYO_FEED));
        }

        @Test
        @DisplayName("Cada tarjeta lleva también la especie y la foto de la alerta, para poder comparar")
        void cadaTarjetaLlevaLaAlerta() {
            Avistamiento candidato = avistamientoDe(alerta(EstadoAlerta.ACTIVA));
            when(avistamientoRepository.buscarCandidatosParaFeed(
                    any(), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(candidato));

            List<AvistamientoFeedResponse> feed = avistamientoService.feed(ID_USUARIO);

            assertThat(feed).hasSize(1);
            assertThat(feed.get(0).getAlertaId()).isEqualTo(ID_ALERTA);
            assertThat(feed.get(0).getAlertaEspecie()).isEqualTo("perro");
            assertThat(feed.get(0).getAlertaFotoUrl()).isEqualTo("https://fotos/alerta.jpg");
            assertThat(feed.get(0).getFotoUrl()).isEqualTo("https://fotos/avistamiento.jpg");
        }
    }

    // ---------------------------------------------------------------------

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
