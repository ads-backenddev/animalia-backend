package com.rescateanimal.backend.service;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Notificacion;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import com.rescateanimal.backend.repository.NotificacionRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    @Mock
    private PlatformTransactionManager transacciones;

    @InjectMocks
    private NotificacionService notificacionService;

    /** Las pruebas que abren una sincronización la dejan siempre cerrada. */
    @AfterEach
    void cerrarSincronizacion() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Nested
    @DisplayName("NUEVO_AVISTAMIENTO, tras el commit")
    class NuevoAvistamiento {

        private static final Long ID_ALERTA = 50L;
        private static final Long ID_DUENO = 2L;
        private static final Long ID_TESTIGO = 3L;

        @Test
        @DisplayName("No se crea nada hasta el commit; después, un aviso al dueño que apunta a la ALERTA")
        void seCreaTrasElCommit() {
            when(usuarioRepository.findById(ID_DUENO)).thenReturn(Optional.of(usuario(ID_DUENO)));
            TransactionSynchronizationManager.initSynchronization();

            notificacionService.avisarNuevoAvistamiento(ID_ALERTA, ID_DUENO, ID_TESTIGO);
            verifyNoInteractions(notificacionRepository);

            confirmar();

            ArgumentCaptor<Notificacion> guardada = ArgumentCaptor.forClass(Notificacion.class);
            verify(notificacionRepository).save(guardada.capture());
            assertThat(guardada.getValue().getTipo()).isEqualTo(TipoNotificacion.NUEVO_AVISTAMIENTO);
            assertThat(guardada.getValue().getReferenciaId()).isEqualTo(ID_ALERTA);
            assertThat(guardada.getValue().getUsuario().getId()).isEqualTo(ID_DUENO);
            assertThat(guardada.getValue().getLeida()).isFalse();
        }

        @Test
        @DisplayName("Sin commit —la operación se deshizo— no hay aviso")
        void sinCommitNoHayAviso() {
            TransactionSynchronizationManager.initSynchronization();

            notificacionService.avisarNuevoAvistamiento(ID_ALERTA, ID_DUENO, ID_TESTIGO);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verifyNoInteractions(notificacionRepository);
        }

        @Test
        @DisplayName("Se escribe en una transacción nueva: la de quien llama ya está confirmada")
        void enTransaccionNueva() {
            when(usuarioRepository.findById(ID_DUENO)).thenReturn(Optional.of(usuario(ID_DUENO)));
            TransactionSynchronizationManager.initSynchronization();

            notificacionService.avisarNuevoAvistamiento(ID_ALERTA, ID_DUENO, ID_TESTIGO);
            confirmar();

            verify(transacciones).getTransaction(argThat(definicion ->
                    definicion.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        }

        @Test
        @DisplayName("El dueño que registra un avistamiento en su propia alerta no recibe aviso")
        void elDuenoNoSeAvisaASiMismo() {
            TransactionSynchronizationManager.initSynchronization();

            notificacionService.avisarNuevoAvistamiento(ID_ALERTA, ID_DUENO, ID_DUENO);

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verifyNoInteractions(notificacionRepository, usuarioRepository);
        }

        @Test
        @DisplayName("A una cuenta inactiva no se le avisa")
        void cuentaInactiva() {
            Usuario inactivo = usuario(ID_DUENO);
            inactivo.setActivo(false);
            when(usuarioRepository.findById(ID_DUENO)).thenReturn(Optional.of(inactivo));

            notificacionService.avisarNuevoAvistamiento(ID_ALERTA, ID_DUENO, ID_TESTIGO);

            verifyNoInteractions(notificacionRepository);
        }

        @Test
        @DisplayName("Un fallo creando el aviso no llega a quien registró el avistamiento")
        void unFalloNoSePropaga() {
            when(usuarioRepository.findById(ID_DUENO)).thenReturn(Optional.of(usuario(ID_DUENO)));
            when(notificacionRepository.save(any())).thenThrow(new IllegalStateException("base de datos caída"));
            TransactionSynchronizationManager.initSynchronization();

            notificacionService.avisarNuevoAvistamiento(ID_ALERTA, ID_DUENO, ID_TESTIGO);

            assertThatCode(this::confirmar).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Sin transacción activa no hay commit que esperar: se crea en el momento")
        void sinTransaccionSeCreaEnElMomento() {
            when(usuarioRepository.findById(ID_DUENO)).thenReturn(Optional.of(usuario(ID_DUENO)));

            notificacionService.avisarNuevoAvistamiento(ID_ALERTA, ID_DUENO, ID_TESTIGO);

            verify(notificacionRepository).save(any());
        }

        private void confirmar() {
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        }
    }

    @Nested
    @DisplayName("ALERTA_ENCONTRADA, tras el commit")
    class AlertaEncontrada {

        private static final Long ID_ALERTA = 50L;
        private static final Long ID_DUENO = 2L;

        @Test
        @DisplayName("Tras el commit, un aviso por participante con la alerta como referencia")
        void unAvisoPorParticipante() {
            when(usuarioRepository.buscarParticipantesDeChatsDeLaAlerta(
                    ID_ALERTA, ID_DUENO, TipoPublicacion.AVISTAMIENTO))
                    .thenReturn(List.of(usuario(3L), usuario(4L)));
            TransactionSynchronizationManager.initSynchronization();

            notificacionService.avisarAlertaEncontrada(ID_ALERTA, ID_DUENO);
            verifyNoInteractions(notificacionRepository, usuarioRepository);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Notificacion>> guardadas = ArgumentCaptor.forClass(List.class);
            verify(notificacionRepository).saveAll(guardadas.capture());
            assertThat(guardadas.getValue())
                    .allSatisfy(n -> {
                        assertThat(n.getTipo()).isEqualTo(TipoNotificacion.ALERTA_ENCONTRADA);
                        assertThat(n.getReferenciaId()).isEqualTo(ID_ALERTA);
                    })
                    .extracting(n -> n.getUsuario().getId())
                    .containsExactly(3L, 4L);
        }

        @Test
        @DisplayName("Sin commit no se busca a nadie ni se avisa")
        void sinCommitNoHayAviso() {
            TransactionSynchronizationManager.initSynchronization();

            notificacionService.avisarAlertaEncontrada(ID_ALERTA, ID_DUENO);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verifyNoInteractions(notificacionRepository, usuarioRepository);
        }
    }

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
