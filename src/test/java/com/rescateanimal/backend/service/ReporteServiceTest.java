package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.CrearReporteRequest;
import com.rescateanimal.backend.dto.response.ReporteResponse;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Reporte;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoReporte;
import com.rescateanimal.backend.model.enums.MotivoReporte;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.model.enums.TipoObjetivo;
import com.rescateanimal.backend.repository.ReporteRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del reporte de contenido y de usuarios (RF-26).
 *
 * DOS COSAS QUE ESTE FICHERO VIGILA. La primera es que no se pueda reportar
 * humo: un reporte contra algo que no existe llena la cola del administrador de
 * trabajo imposible de juzgar. La segunda, más importante, es que un reporte no
 * enseñe nada de la persona reportada; es una acusación sin revisar, y no puede
 * convertirse en una forma de sacarle los datos a alguien.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReporteServiceTest {

    private static final Long ID_AUTOR = 9L;
    private static final Long ID_OBJETIVO = 33L;

    @Mock
    private ReporteRepository reporteRepository;
    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private PublicacionService publicacionService;

    private ReporteService reporteService;

    @BeforeEach
    void prepararServicio() {
        reporteService = new ReporteService(reporteRepository, usuarioRepository, publicacionService);

        when(usuarioRepository.findById(ID_AUTOR)).thenReturn(Optional.of(usuario(ID_AUTOR)));
        when(usuarioRepository.existsById(ID_OBJETIVO)).thenReturn(true);
        when(reporteRepository.save(any())).thenAnswer(invocacion -> {
            Reporte guardado = invocacion.getArgument(0);
            guardado.setId(1L);
            return guardado;
        });
    }

    @Nested
    @DisplayName("Reportar una publicación")
    class Publicaciones {

        @ParameterizedTest(name = "{0}")
        @EnumSource(TipoContenido.class)
        @DisplayName("Se admiten los tres tipos de publicación, y cada uno se comprueba en su tabla")
        void losTresTiposSeAdmiten(TipoContenido tipo) {
            ReporteResponse creado = reporteService.reportar(
                    publicacion(tipo, ID_OBJETIVO), ID_AUTOR);

            verify(publicacionService).exigirQueExista(tipo, ID_OBJETIVO);
            assertThat(creado.getTipoContenido()).isEqualTo(tipo);
            assertThat(creado.getEstado()).isEqualTo(EstadoReporte.PENDIENTE);
        }

        @Test
        @DisplayName("Reportar una publicación inexistente da 404 y no guarda nada")
        void laPublicacionInexistenteDa404() {
            doThrowNoExiste();

            assertThatThrownBy(() -> reporteService.reportar(
                    publicacion(TipoContenido.ALERTA, 999L), ID_AUTOR))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(reporteRepository, never()).save(any());
        }

        @Test
        @DisplayName("Sin tipoContenido no se sabe a qué tabla apunta: 400")
        void laPublicacionSinTipoContenidoDa400() {
            CrearReporteRequest peticion = publicacion(null, ID_OBJETIVO);

            assertThatThrownBy(() -> reporteService.reportar(peticion, ID_AUTOR))
                    .isInstanceOf(ParametrosInvalidosException.class)
                    .hasMessageContaining("tipoContenido");

            verify(reporteRepository, never()).save(any());
        }

        @Test
        @DisplayName("El reporte nace PENDIENTE, lo pida quien lo pida")
        void naceComoPendiente() {
            reporteService.reportar(publicacion(TipoContenido.ADOPCION, ID_OBJETIVO), ID_AUTOR);

            ArgumentCaptor<Reporte> guardado = ArgumentCaptor.forClass(Reporte.class);
            verify(reporteRepository).save(guardado.capture());
            assertThat(guardado.getValue().getEstado()).isEqualTo(EstadoReporte.PENDIENTE);
        }
    }

    @Nested
    @DisplayName("Reportar a un usuario")
    class Usuarios {

        @Test
        @DisplayName("Un usuario existente se reporta sin tipoContenido")
        void elUsuarioSeReporta() {
            ReporteResponse creado = reporteService.reportar(usuarioObjetivo(ID_OBJETIVO), ID_AUTOR);

            assertThat(creado.getTipoObjetivo()).isEqualTo(TipoObjetivo.USUARIO);
            assertThat(creado.getTipoContenido())
                    .as("un id de usuario no necesita discriminador")
                    .isNull();
            verify(publicacionService, never()).exigirQueExista(any(), any());
        }

        @Test
        @DisplayName("Un usuario inexistente da 404")
        void elUsuarioInexistenteDa404() {
            when(usuarioRepository.existsById(404L)).thenReturn(false);

            assertThatThrownBy(() -> reporteService.reportar(usuarioObjetivo(404L), ID_AUTOR))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(reporteRepository, never()).save(any());
        }

        @Test
        @DisplayName("Con tipoContenido sobra: un usuario no vive en tres tablas")
        void elUsuarioConTipoContenidoDa400() {
            CrearReporteRequest peticion = usuarioObjetivo(ID_OBJETIVO);
            peticion.setTipoContenido(TipoContenido.ALERTA);

            assertThatThrownBy(() -> reporteService.reportar(peticion, ID_AUTOR))
                    .isInstanceOf(ParametrosInvalidosException.class);
        }

        @Test
        @DisplayName("Reportarse a uno mismo no es una operación del dominio")
        void nadieSeReportaASiMismo() {
            assertThatThrownBy(() -> reporteService.reportar(usuarioObjetivo(ID_AUTOR), ID_AUTOR))
                    .isInstanceOf(OperacionNoPermitidaException.class)
                    .hasMessageContaining("ti mismo");

            verify(reporteRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Qué se devuelve, y qué no")
    class Privacidad {

        @Test
        @DisplayName("Del autor solo id, nombre y foto: ni email ni teléfono (RNF-10)")
        void elAutorVaSinDatosDeContacto() {
            ReporteResponse creado = reporteService.reportar(
                    publicacion(TipoContenido.ALERTA, ID_OBJETIVO), ID_AUTOR);

            assertThat(creado.getAutor().getId()).isEqualTo(ID_AUTOR);
            assertThat(creado.getAutor().getNombre()).isEqualTo("Ana");
            // AutorResponse no tiene siquiera dónde meterlos, y esa es la
            // garantía: no es que no se rellenen, es que no caben.
            assertThat(creado.getAutor()).hasNoNullFieldsOrPropertiesExcept("fotoUrl");
        }

        @Test
        @DisplayName("Del reportado solo el par (tipo, id): nunca sus datos")
        void delReportadoSoloElPar() {
            ReporteResponse creado = reporteService.reportar(usuarioObjetivo(ID_OBJETIVO), ID_AUTOR);

            assertThat(creado.getObjetivoId()).isEqualTo(ID_OBJETIVO);
            // Y no se consulta al usuario reportado para nada más que comprobar
            // que existe: nada de su fila llega a la respuesta.
            verify(usuarioRepository).existsById(ID_OBJETIVO);
            verify(usuarioRepository, never()).findById(ID_OBJETIVO);
        }
    }

    // ---------------------------------------------------------------------

    private void doThrowNoExiste() {
        doThrow(new ResourceNotFoundException("no existe"))
                .when(publicacionService).exigirQueExista(any(), any());
    }

    private static CrearReporteRequest publicacion(TipoContenido tipo, Long objetivoId) {
        return new CrearReporteRequest(
                TipoObjetivo.PUBLICACION, objetivoId, tipo, MotivoReporte.CONTENIDO_INAPROPIADO);
    }

    private static CrearReporteRequest usuarioObjetivo(Long objetivoId) {
        return new CrearReporteRequest(
                TipoObjetivo.USUARIO, objetivoId, null, MotivoReporte.ACOSO);
    }

    private static Usuario usuario(Long id) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setNombre("Ana");
        usuario.setEmail("ana@test.es");
        usuario.setTelefono("600000000");
        usuario.setFotoUrl("https://example.test/ana.jpg");
        usuario.setRol(Rol.CIUDADANO);
        usuario.setActivo(true);
        return usuario;
    }
}
