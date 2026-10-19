package com.rescateanimal.backend.service;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Aviso a los usuarios de la zona cuando se publica una alerta (RF-05).
 *
 * Lo que se prueba aquí es a quién se notifica y a quién no, que es donde está
 * la decisión: el autor nunca se notifica a sí mismo, las cuentas bloqueadas
 * quedan fuera y un área vacía no dispara nada. El envío push no entra porque
 * todavía no existe: por ahora la notificación solo se persiste.
 *
 * La comparación de zonas es un match de texto sobre Usuario.areaGeografica,
 * no una distancia real, porque el SRS define ese campo como una cadena. Las
 * pruebas reflejan esa limitación en lugar de disimularla.
 */
@ExtendWith(MockitoExtension.class)
class NotificacionServiceTest {

    private static final Long ID_AUTOR = 1L;
    private static final String AREA = "Madrid";

    @Mock
    private NotificacionRepository notificacionRepository;
    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private NotificacionService notificacionService;

    @Nested
    @DisplayName("A quién se notifica")
    class Destinatarios {

        @Test
        @DisplayName("Se crea una notificación ALERTA_CERCANA por cada usuario del área")
        void notificaACadaUsuarioDelArea() {
            when(usuarioRepository.findByAreaGeograficaIgnoreCaseAndActivoTrueAndIdNot(AREA, ID_AUTOR))
                    .thenReturn(List.of(usuario(2L), usuario(3L)));

            int creadas = notificacionService.notificarAlertaCercana(alerta(50L, AREA));

            assertThat(creadas).isEqualTo(2);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Notificacion>> guardadas = ArgumentCaptor.forClass(List.class);
            verify(notificacionRepository).saveAll(guardadas.capture());

            assertThat(guardadas.getValue())
                    .hasSize(2)
                    .allSatisfy(n -> {
                        assertThat(n.getTipo()).isEqualTo(TipoNotificacion.ALERTA_CERCANA);
                        // referenciaId apunta a la alerta: es lo que permitirá al
                        // cliente abrirla desde la notificación.
                        assertThat(n.getReferenciaId()).isEqualTo(50L);
                        assertThat(n.getLeida()).isFalse();
                    })
                    .extracting(n -> n.getUsuario().getId())
                    .containsExactly(2L, 3L);
        }

        @Test
        @DisplayName("El autor de la alerta queda excluido: la consulta pide su id distinto")
        void elAutorNoSeNotificaASiMismo() {
            when(usuarioRepository.findByAreaGeograficaIgnoreCaseAndActivoTrueAndIdNot(AREA, ID_AUTOR))
                    .thenReturn(List.of());

            notificacionService.notificarAlertaCercana(alerta(50L, AREA));

            // Es la consulta la que excluye al autor y a las cuentas bloqueadas
            // (...ActivoTrueAndIdNot); si alguien cambiara ese nombre de método,
            // esta verificación es la que lo delata.
            verify(usuarioRepository)
                    .findByAreaGeograficaIgnoreCaseAndActivoTrueAndIdNot(AREA, ID_AUTOR);
        }

        @Test
        @DisplayName("Sin nadie más en el área no se guarda ninguna notificación")
        void sinDestinatariosNoCreaNada() {
            when(usuarioRepository.findByAreaGeograficaIgnoreCaseAndActivoTrueAndIdNot(AREA, ID_AUTOR))
                    .thenReturn(List.of());

            int creadas = notificacionService.notificarAlertaCercana(alerta(50L, AREA));

            assertThat(creadas).isZero();
            verify(notificacionRepository).saveAll(List.of());
        }
    }

    @Nested
    @DisplayName("Alertas sin área utilizable")
    class SinArea {

        @Test
        @DisplayName("Autor sin área geográfica -> no se notifica a nadie ni se consulta la BD")
        void areaNulaNoNotifica() {
            int creadas = notificacionService.notificarAlertaCercana(alerta(50L, null));

            assertThat(creadas).isZero();
            verifyNoInteractions(notificacionRepository);
            verify(usuarioRepository, never())
                    .findByAreaGeograficaIgnoreCaseAndActivoTrueAndIdNot(anyString(), any());
        }

        @Test
        @DisplayName("Área en blanco -> se trata igual que si no la hubiera")
        void areaEnBlancoNoNotifica() {
            int creadas = notificacionService.notificarAlertaCercana(alerta(50L, "   "));

            assertThat(creadas).isZero();
            verifyNoInteractions(notificacionRepository);
        }
    }

    // ------------------------------------------------------------------

    private static Alerta alerta(Long id, String areaDelAutor) {
        Usuario autor = usuario(ID_AUTOR);
        autor.setAreaGeografica(areaDelAutor);

        Alerta alerta = new Alerta();
        alerta.setId(id);
        alerta.setUsuario(autor);
        return alerta;
    }

    private static Usuario usuario(Long id) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setNombre("Usuario " + id);
        usuario.setEmail("usuario" + id + "@test.es");
        usuario.setRol(Rol.CIUDADANO);
        usuario.setActivo(true);
        usuario.setAreaGeografica(AREA);
        return usuario;
    }
}
