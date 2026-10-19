package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.ValoracionResponse;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.exception.SinInteraccionPreviaException;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.Valoracion;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.MatchRegistroRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.repository.ValoracionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de las valoraciones sobre una protectora (RF-23).
 *
 * LO QUE MÁS IMPORTA AQUÍ NO ES QUE SE PUEDA VALORAR, sino que no se pueda
 * valorar de cualquier manera. RF-23 pide una valoración "tras una adopción o
 * contacto", y sin esa condición la nota de una protectora sería un número que
 * cualquiera puede mover sin haber tratado con ella. Por eso el grupo de
 * rechazos es el más largo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ValoracionServiceTest {

    private static final Long ID_PROTECTORA = 70L;
    private static final Long ID_USUARIO = 9L;

    @Mock
    private ValoracionRepository valoracionRepository;
    @Mock
    private ProtectoraRepository protectoraRepository;
    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private MatchRegistroRepository matchRegistroRepository;

    private ValoracionService valoracionService;

    @BeforeEach
    void prepararServicio() {
        valoracionService = new ValoracionService(
                valoracionRepository, protectoraRepository, usuarioRepository,
                matchRegistroRepository);

        when(protectoraRepository.findById(ID_PROTECTORA)).thenReturn(Optional.of(protectora()));
        when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario()));
        when(matchRegistroRepository.existeContactoConProtectora(
                ID_USUARIO, ID_PROTECTORA, TipoPublicacion.ADOPCION)).thenReturn(true);
        when(valoracionRepository.findByUsuarioIdAndProtectoraId(ID_USUARIO, ID_PROTECTORA))
                .thenReturn(Optional.empty());
        when(valoracionRepository.save(any())).thenAnswer(invocacion -> {
            Valoracion guardada = invocacion.getArgument(0);
            if (guardada.getId() == null) {
                guardada.setId(1L);
            }
            return guardada;
        });
    }

    @Nested
    @DisplayName("Sin interacción previa no se valora")
    class Requisito {

        @Test
        @DisplayName("Quien nunca ha contactado con la protectora recibe 403, no una valoración")
        void sinContactoNoSePuedeValorar() {
            when(matchRegistroRepository.existeContactoConProtectora(
                    ID_USUARIO, ID_PROTECTORA, TipoPublicacion.ADOPCION)).thenReturn(false);

            assertThatThrownBy(() -> valoracionService.valorar(ID_PROTECTORA, ID_USUARIO, 5))
                    .isInstanceOf(SinInteraccionPreviaException.class)
                    .hasMessageContaining("hayas contactado");

            // Y no se guarda nada: el rechazo es antes de tocar la tabla.
            verify(valoracionRepository, never()).save(any());
        }

        @Test
        @DisplayName("El contacto se busca sobre publicaciones de ADOPCIÓN, no sobre cualquier match")
        void elContactoEsSobreAdopcion() {
            // Deslizar un avistamiento no es contactar con una protectora: si el
            // filtro de tipo desapareciera, el avistamiento 7 contaría como
            // contacto con la dueña del animal 7, que no tiene nada que ver.
            valoracionService.valorar(ID_PROTECTORA, ID_USUARIO, 4);

            verify(matchRegistroRepository).existeContactoConProtectora(
                    ID_USUARIO, ID_PROTECTORA, TipoPublicacion.ADOPCION);
        }

        @Test
        @DisplayName("Una protectora inexistente da 404, y se comprueba antes que el contacto")
        void laProtectoraInexistenteDa404() {
            when(protectoraRepository.findById(ID_PROTECTORA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> valoracionService.valorar(ID_PROTECTORA, ID_USUARIO, 5))
                    .isInstanceOf(ResourceNotFoundException.class);

            // Si el orden fuera el contrario, valorar algo inexistente diría "no
            // puedes valorarla" en lugar de "no existe".
            verify(matchRegistroRepository, never())
                    .existeContactoConProtectora(anyLong(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Valorar dos veces actualiza, no duplica")
    class Idempotencia {

        @Test
        @DisplayName("La segunda valoración reemplaza la puntuación de la primera")
        void laSegundaActualizaLaPrimera() {
            Valoracion previa = new Valoracion();
            previa.setId(55L);
            previa.setUsuario(usuario());
            previa.setProtectora(protectora());
            previa.setPuntuacion(2);
            when(valoracionRepository.findByUsuarioIdAndProtectoraId(ID_USUARIO, ID_PROTECTORA))
                    .thenReturn(Optional.of(previa));

            ValoracionResponse respuesta = valoracionService.valorar(ID_PROTECTORA, ID_USUARIO, 5);

            ArgumentCaptor<Valoracion> guardada = ArgumentCaptor.forClass(Valoracion.class);
            verify(valoracionRepository).save(guardada.capture());

            assertThat(guardada.getValue().getId())
                    .as("tiene que guardar la fila que ya existía, no una nueva")
                    .isEqualTo(55L);
            assertThat(guardada.getValue().getPuntuacion()).isEqualTo(5);
            assertThat(respuesta.getPuntuacion()).isEqualTo(5);
        }

        @Test
        @DisplayName("La primera valoración sí crea fila nueva")
        void laPrimeraCreaFila() {
            valoracionService.valorar(ID_PROTECTORA, ID_USUARIO, 3);

            ArgumentCaptor<Valoracion> guardada = ArgumentCaptor.forClass(Valoracion.class);
            verify(valoracionRepository).save(guardada.capture());

            assertThat(guardada.getValue().getUsuario().getId()).isEqualTo(ID_USUARIO);
            assertThat(guardada.getValue().getProtectora().getId()).isEqualTo(ID_PROTECTORA);
            assertThat(guardada.getValue().getPuntuacion()).isEqualTo(3);
        }

        @Test
        @DisplayName("Repetir la misma puntuación no cambia nada, que es lo que promete un PUT")
        void repetirLaMismaEsInocuo() {
            Valoracion previa = new Valoracion();
            previa.setId(55L);
            previa.setUsuario(usuario());
            previa.setProtectora(protectora());
            previa.setPuntuacion(4);
            when(valoracionRepository.findByUsuarioIdAndProtectoraId(ID_USUARIO, ID_PROTECTORA))
                    .thenReturn(Optional.of(previa));

            assertThat(valoracionService.valorar(ID_PROTECTORA, ID_USUARIO, 4).getPuntuacion())
                    .isEqualTo(4);
            assertThat(previa.getPuntuacion()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("La media se calcula, no se guarda")
    class Media {

        @Test
        @DisplayName("Media y total salen de la tabla de valoraciones")
        void laMediaSaleDeLasValoraciones() {
            when(valoracionRepository.mediaDeProtectora(ID_PROTECTORA)).thenReturn(4.0);
            when(valoracionRepository.countByProtectoraId(ID_PROTECTORA)).thenReturn(3L);

            ValoracionService.ResumenValoraciones resumen =
                    valoracionService.resumenDe(ID_PROTECTORA);

            assertThat(resumen.media()).isEqualByComparingTo("4.00");
            assertThat(resumen.total()).isEqualTo(3);
        }

        @Test
        @DisplayName("Sin valoraciones la media es nula, no cero")
        void sinValoracionesNoHayMedia() {
            when(valoracionRepository.mediaDeProtectora(ID_PROTECTORA)).thenReturn(null);
            when(valoracionRepository.countByProtectoraId(ID_PROTECTORA)).thenReturn(0L);

            ValoracionService.ResumenValoraciones resumen =
                    valoracionService.resumenDe(ID_PROTECTORA);

            // Un cero se pintaría como "pésima" y sería falso: no es que valga
            // cero, es que no la hay.
            assertThat(resumen.media()).isNull();
            assertThat(resumen.total()).isZero();
        }

        @ParameterizedTest(name = "AVG {0} -> {1}")
        @CsvSource({
                "4.0,      4.00",
                "4.125,    4.13",
                "4.124,    4.12",
                "3.3333333, 3.33",
                "1.0,      1.00",
                "5.0,      5.00"
        })
        @DisplayName("Se redondea a dos decimales, al alza en el empate")
        void seRedondeaADosDecimales(double media, String esperado) {
            assertThat(ValoracionService.redondear(media)).isEqualByComparingTo(esperado);
        }
    }

    @Nested
    @DisplayName("Lectura para el directorio (RF-06)")
    class Directorio {

        /**
         * EL CASO QUE SE OLVIDA. Un GROUP BY no devuelve grupos vacíos, así que la
         * protectora sin ninguna valoración no aparece en el resultado de la
         * consulta. Si el mapa se construyera solo con lo que vuelve, quien lo lea
         * se encontraría un null justo en la protectora recién verificada, que es
         * la que más probable es que no tenga ninguna.
         */
        @Test
        @DisplayName("Devuelve un resumen por cada id pedido, también por los que no tienen ninguna")
        void ningunIdSeQuedaSinResumen() {
            when(valoracionRepository.resumirPorProtectora(any()))
                    .thenReturn(List.of(fila(ID_PROTECTORA, 4.5, 8L)));

            var resumenes = valoracionService.resumenDeVarias(List.of(ID_PROTECTORA, 71L, 72L));

            assertThat(resumenes).containsOnlyKeys(ID_PROTECTORA, 71L, 72L);
            assertThat(resumenes.get(ID_PROTECTORA).media()).isEqualByComparingTo("4.50");
            assertThat(resumenes.get(ID_PROTECTORA).total()).isEqualTo(8);

            // Sin valoraciones: media nula, total conocido y cero.
            assertThat(resumenes.get(71L).media()).isNull();
            assertThat(resumenes.get(71L).total()).isZero();
        }

        @Test
        @DisplayName("Sin ids que consultar no se pregunta a la base de datos")
        void sinIdsNoSeConsulta() {
            assertThat(valoracionService.resumenDeVarias(List.of())).isEmpty();

            verify(valoracionRepository, never()).resumirPorProtectora(any());
        }

        @Test
        @DisplayName("Redondea igual que el resumen de una sola, para que los dos coincidan")
        void redondeaIgualQueElIndividual() {
            when(valoracionRepository.resumirPorProtectora(any()))
                    .thenReturn(List.of(fila(ID_PROTECTORA, 4.125, 4L)));
            when(valoracionRepository.mediaDeProtectora(ID_PROTECTORA)).thenReturn(4.125);
            when(valoracionRepository.countByProtectoraId(ID_PROTECTORA)).thenReturn(4L);

            var enLote = valoracionService.resumenDeVarias(List.of(ID_PROTECTORA))
                    .get(ID_PROTECTORA);
            var individual = valoracionService.resumenDe(ID_PROTECTORA);

            assertThat(enLote).isEqualTo(individual);
        }

        /**
         * La ficha pública se sirve en cada visita al directorio: traer el
         * histórico entero para pintar las primeras sería trabajo tirado. El tope
         * lo fija quien llama, y aquí se comprueba que llega a la consulta.
         */
        @Test
        @DisplayName("Las últimas valoraciones se piden con el tope que da quien llama")
        void lasUltimasSePidenConTope() {
            when(valoracionRepository.buscarDeProtectoraConAutor(anyLong(), any()))
                    .thenReturn(List.of());

            valoracionService.ultimasDe(ID_PROTECTORA, 10);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(valoracionRepository)
                    .buscarDeProtectoraConAutor(eq(ID_PROTECTORA), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(10);
            assertThat(captor.getValue().getPageNumber()).isZero();
        }

        @Test
        @DisplayName("Un tope de cero o negativo no se pasa a la consulta, que lo rechazaría")
        void unTopeAbsurdoSeCorrige() {
            when(valoracionRepository.buscarDeProtectoraConAutor(anyLong(), any()))
                    .thenReturn(List.of());

            valoracionService.ultimasDe(ID_PROTECTORA, 0);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(valoracionRepository)
                    .buscarDeProtectoraConAutor(eq(ID_PROTECTORA), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("El autor viaja sin email ni teléfono, como en el resto de la API")
        void elAutorViajaSinDatosDeContacto() {
            Valoracion emitida = new Valoracion();
            emitida.setId(1L);
            emitida.setUsuario(usuario());
            emitida.setProtectora(protectora());
            emitida.setPuntuacion(5);
            when(valoracionRepository.buscarDeProtectoraConAutor(anyLong(), any()))
                    .thenReturn(List.of(emitida));

            ValoracionResponse respuesta = valoracionService.ultimasDe(ID_PROTECTORA, 10).get(0);

            // La excepción de RF-06 es el contacto de la PROTECTORA, no el de quien
            // la valora: aquí sigue mandando RNF-10.
            assertThat(respuesta.getAutor().getNombre()).isEqualTo("Ana");
            assertThat(respuesta.getPuntuacion()).isEqualTo(5);
        }
    }

    // ---------------------------------------------------------------------

    /** Una fila de la agregación por protectora, que en producción monta Spring Data. */
    private static ValoracionRepository.ResumenPorProtectora fila(Long protectoraId,
                                                                 Double media, long total) {
        return new ValoracionRepository.ResumenPorProtectora() {
            @Override
            public Long getProtectoraId() {
                return protectoraId;
            }

            @Override
            public Double getMedia() {
                return media;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }

    private static Protectora protectora() {
        Protectora protectora = new Protectora();
        protectora.setId(ID_PROTECTORA);
        protectora.setNombreOrganizacion("Protectora de prueba");
        protectora.setEstado(EstadoProtectora.ACTIVA);
        return protectora;
    }

    private static Usuario usuario() {
        Usuario usuario = new Usuario();
        usuario.setId(ID_USUARIO);
        usuario.setNombre("Ana");
        usuario.setEmail("ana@test.es");
        usuario.setRol(Rol.CIUDADANO);
        usuario.setActivo(true);
        return usuario;
    }
}
