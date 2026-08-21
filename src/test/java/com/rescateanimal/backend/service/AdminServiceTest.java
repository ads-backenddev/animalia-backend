package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.dto.response.ProtectoraAdminResponse;
import com.rescateanimal.backend.dto.response.UsuarioAdminResponse;
import com.rescateanimal.backend.exception.AutobloqueoException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.EstadoReporte;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoContenido;
import com.rescateanimal.backend.model.enums.TipoNotificacion;
import com.rescateanimal.backend.repository.AlertaRepository;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.AvistamientoRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.ReporteRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas del panel de administración (RF-11).
 *
 * Aquí NO se comprueba el rol: de eso se ocupa el @PreAuthorize de la clase
 * AdminController y lo demuestra AdminControllerTest. Lo que se fija en este
 * fichero es qué hace cada decisión una vez concedido el permiso: a quién avisa,
 * qué estado deja, y qué NO cambia cuando la operación se repite.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminServiceTest {

    private static final Long ID_ADMIN = 1L;
    private static final Long ID_USUARIO = 7L;
    private static final Long ID_PROTECTORA = 20L;
    private static final Long ID_PUBLICACION = 33L;

    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private ProtectoraRepository protectoraRepository;
    @Mock
    private ReporteRepository reporteRepository;
    @Mock
    private AlertaRepository alertaRepository;
    @Mock
    private AvistamientoRepository avistamientoRepository;
    @Mock
    private AnimalAdopcionRepository animalAdopcionRepository;
    @Mock
    private NotificacionService notificacionService;

    private AdminService admin;

    @BeforeEach
    void prepararServicio() {
        admin = new AdminService(usuarioRepository, protectoraRepository, reporteRepository,
                alertaRepository, avistamientoRepository, animalAdopcionRepository,
                notificacionService);
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Listado de usuarios (RF-11)")
    class Listado {

        @Test
        @DisplayName("Sin filtros pide la página tal cual, ordenada por alta descendente")
        void sinFiltrosDevuelveTodos() {
            when(usuarioRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(usuario(ID_USUARIO, Rol.CIUDADANO, true))));

            PaginaResponse<UsuarioAdminResponse> pagina =
                    admin.listarUsuarios(null, null, PageRequest.of(0, 20));

            assertThat(pagina.getContent()).hasSize(1);
            assertThat(pagina.getContent().get(0).getId()).isEqualTo(ID_USUARIO);

            ArgumentCaptor<Pageable> peticion = ArgumentCaptor.forClass(Pageable.class);
            verify(usuarioRepository).findAll(any(Specification.class), peticion.capture());
            assertThat(peticion.getValue().getSort())
                    .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        }

        @Test
        @DisplayName("El tamaño de página se recorta al máximo, se pida lo que se pida")
        void recortaElTamanyoDePagina() {
            when(usuarioRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            admin.listarUsuarios(null, null, PageRequest.of(0, 5000));

            ArgumentCaptor<Pageable> peticion = ArgumentCaptor.forClass(Pageable.class);
            verify(usuarioRepository).findAll(any(Specification.class), peticion.capture());
            assertThat(peticion.getValue().getPageSize()).isEqualTo(AdminService.MAXIMO_POR_PAGINA);
        }

        @Test
        @DisplayName("La respuesta lleva el email —el administrador lo necesita— pero nunca el teléfono")
        void noExponeElTelefono() {
            Usuario usuario = usuario(ID_USUARIO, Rol.CIUDADANO, true);
            usuario.setTelefono("600111222");
            when(usuarioRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(usuario)));

            UsuarioAdminResponse respuesta =
                    admin.listarUsuarios(null, null, PageRequest.of(0, 20)).getContent().get(0);

            assertThat(respuesta.getEmail()).isEqualTo("usuario7@test.es");
            // El DTO no tiene teléfono: si alguien lo añadiera, esto deja de compilar.
            assertThat(respuesta).hasNoNullFieldsOrPropertiesExcept("areaGeografica", "createdAt");
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Bloqueo y desbloqueo (RF-11)")
    class Bloqueo {

        @Test
        @DisplayName("Bloquear deja la cuenta inactiva y avisa al afectado")
        void bloquearDesactivaYNotifica() {
            Usuario usuario = usuario(ID_USUARIO, Rol.CIUDADANO, true);
            when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario));

            UsuarioAdminResponse respuesta =
                    admin.bloquear(ID_USUARIO, "Insultos reiterados", ID_ADMIN);

            assertThat(usuario.getActivo()).isFalse();
            assertThat(respuesta.getActivo()).isFalse();
            verify(notificacionService).notificarDecisionAdministrativa(
                    usuario, TipoNotificacion.CUENTA_BLOQUEADA, null);
        }

        @Test
        @DisplayName("El motivo no viaja en la notificación: NOTIFICACION no tiene dónde guardarlo")
        void elMotivoNoViajaEnLaNotificacion() {
            Usuario usuario = usuario(ID_USUARIO, Rol.CIUDADANO, true);
            when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario));

            admin.bloquear(ID_USUARIO, "Insultos reiterados", ID_ADMIN);

            // La referencia es null a propósito: el aviso es sobre la propia
            // cuenta del destinatario, no sobre otra fila a la que apuntar.
            verify(notificacionService).notificarDecisionAdministrativa(
                    any(Usuario.class), eq(TipoNotificacion.CUENTA_BLOQUEADA), eq(null));
        }

        @Test
        @DisplayName("Bloquear lo ya bloqueado no cambia nada ni vuelve a avisar")
        void bloquearEsIdempotente() {
            Usuario usuario = usuario(ID_USUARIO, Rol.CIUDADANO, false);
            when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario));

            admin.bloquear(ID_USUARIO, "Otra vez", ID_ADMIN);

            assertThat(usuario.getActivo()).isFalse();
            verifyNoInteractions(notificacionService);
        }

        @Test
        @DisplayName("Nadie se bloquea a sí mismo: sería la única puerta sin retorno")
        void nadieSeBloqueaASiMismo() {
            // AutobloqueoException y no OperacionNoPermitidaException: esta última
            // se traduce a 400, y aquí la petición está bien formada y el rol es
            // el correcto. Lo que se rechaza es la operación sobre ese objetivo,
            // que es un 403.
            assertThatThrownBy(() -> admin.bloquear(ID_ADMIN, "Me castigo", ID_ADMIN))
                    .isInstanceOf(AutobloqueoException.class);

            // Y se corta antes de tocar nada: ni se busca el usuario.
            verifyNoInteractions(usuarioRepository, notificacionService);
        }

        @Test
        @DisplayName("Desbloquear reactiva la cuenta y avisa")
        void desbloquearReactivaYNotifica() {
            Usuario usuario = usuario(ID_USUARIO, Rol.CIUDADANO, false);
            when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario));

            UsuarioAdminResponse respuesta = admin.desbloquear(ID_USUARIO, ID_ADMIN);

            assertThat(usuario.getActivo()).isTrue();
            assertThat(respuesta.getActivo()).isTrue();
            verify(notificacionService).notificarDecisionAdministrativa(
                    usuario, TipoNotificacion.CUENTA_DESBLOQUEADA, null);
        }

        @Test
        @DisplayName("Desbloquear lo ya activo no vuelve a avisar")
        void desbloquearEsIdempotente() {
            when(usuarioRepository.findById(ID_USUARIO))
                    .thenReturn(Optional.of(usuario(ID_USUARIO, Rol.CIUDADANO, true)));

            admin.desbloquear(ID_USUARIO, ID_ADMIN);

            verifyNoInteractions(notificacionService);
        }

        @Test
        @DisplayName("Un usuario inexistente da 404")
        void usuarioInexistenteDa404() {
            when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> admin.bloquear(ID_USUARIO, "x", ID_ADMIN))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> admin.desbloquear(ID_USUARIO, ID_ADMIN))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Si falla la notificación, el bloqueo se mantiene: la decisión vale por sí sola")
        void elFalloDelAvisoNoDeshaceElBloqueo() {
            Usuario usuario = usuario(ID_USUARIO, Rol.CIUDADANO, true);
            when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario));
            when(notificacionService.notificarDecisionAdministrativa(any(), any(), any()))
                    .thenThrow(new IllegalStateException("la base de datos se cayó"));

            UsuarioAdminResponse respuesta = admin.bloquear(ID_USUARIO, "Spam", ID_ADMIN);

            assertThat(respuesta.getActivo()).isFalse();
            assertThat(usuario.getActivo()).isFalse();
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Verificación de protectoras (RF-11)")
    class Verificacion {

        @Test
        @DisplayName("La cola de pendientes pide exactamente el estado PENDIENTE")
        void laColaPidePendientes() {
            when(protectoraRepository.buscarPorEstadoConCuenta(EstadoProtectora.PENDIENTE))
                    .thenReturn(List.of(protectora(EstadoProtectora.PENDIENTE)));

            List<ProtectoraAdminResponse> cola = admin.listarPendientes();

            assertThat(cola).hasSize(1);
            assertThat(cola.get(0).getEstado()).isEqualTo(EstadoProtectora.PENDIENTE);
            assertThat(cola.get(0).getUsuarioEmail()).isEqualTo("usuario30@test.es");
            verify(protectoraRepository).buscarPorEstadoConCuenta(EstadoProtectora.PENDIENTE);
        }

        @Test
        @DisplayName("Aprobar una PENDIENTE la deja ACTIVA y genera PROTECTORA_VERIFICADA")
        void aprobarGeneraLaNotificacionCorrecta() {
            Protectora protectora = protectora(EstadoProtectora.PENDIENTE);
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora));

            ProtectoraAdminResponse respuesta = admin.aprobarProtectora(ID_PROTECTORA, ID_ADMIN);

            assertThat(protectora.getEstado()).isEqualTo(EstadoProtectora.ACTIVA);
            assertThat(respuesta.getEstado()).isEqualTo(EstadoProtectora.ACTIVA);
            verify(notificacionService).notificarDecisionAdministrativa(
                    protectora.getUsuario(), TipoNotificacion.PROTECTORA_VERIFICADA, ID_PROTECTORA);
        }

        @Test
        @DisplayName("Rechazar una PENDIENTE la deja RECHAZADA y genera PROTECTORA_RECHAZADA")
        void rechazarGeneraLaNotificacionCorrecta() {
            Protectora protectora = protectora(EstadoProtectora.PENDIENTE);
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora));

            ProtectoraAdminResponse respuesta =
                    admin.rechazarProtectora(ID_PROTECTORA, "Documentación insuficiente", ID_ADMIN);

            assertThat(protectora.getEstado()).isEqualTo(EstadoProtectora.RECHAZADA);
            assertThat(respuesta.getEstado()).isEqualTo(EstadoProtectora.RECHAZADA);
            verify(notificacionService).notificarDecisionAdministrativa(
                    protectora.getUsuario(), TipoNotificacion.PROTECTORA_RECHAZADA, ID_PROTECTORA);
        }

        @Test
        @DisplayName("El aviso va a la cuenta de la protectora, no a un id suelto")
        void elAvisoVaALaCuentaDeLaProtectora() {
            Protectora protectora = protectora(EstadoProtectora.PENDIENTE);
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora));

            admin.aprobarProtectora(ID_PROTECTORA, ID_ADMIN);

            ArgumentCaptor<Usuario> destinatario = ArgumentCaptor.forClass(Usuario.class);
            verify(notificacionService).notificarDecisionAdministrativa(
                    destinatario.capture(), any(), anyLong());
            assertThat(destinatario.getValue().getId()).isEqualTo(30L);
            assertThat(destinatario.getValue().getRol()).isEqualTo(Rol.PROTECTORA);
        }

        @Test
        @DisplayName("Se puede aprobar una RECHAZADA: un rechazo no es una condena")
        void seRecuperaUnaRechazada() {
            Protectora protectora = protectora(EstadoProtectora.RECHAZADA);
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora));

            admin.aprobarProtectora(ID_PROTECTORA, ID_ADMIN);

            assertThat(protectora.getEstado()).isEqualTo(EstadoProtectora.ACTIVA);
            verify(notificacionService).notificarDecisionAdministrativa(
                    any(), eq(TipoNotificacion.PROTECTORA_VERIFICADA), anyLong());
        }

        @Test
        @DisplayName("Y rechazar una ACTIVA: retirar una verificación ya concedida")
        void seRetiraUnaVerificacionConcedida() {
            Protectora protectora = protectora(EstadoProtectora.ACTIVA);
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora));

            admin.rechazarProtectora(ID_PROTECTORA, "Denuncias confirmadas", ID_ADMIN);

            assertThat(protectora.getEstado()).isEqualTo(EstadoProtectora.RECHAZADA);
            verify(notificacionService).notificarDecisionAdministrativa(
                    any(), eq(TipoNotificacion.PROTECTORA_RECHAZADA), anyLong());
        }

        @Test
        @DisplayName("Repetir la decisión no vuelve a avisar de algo que ya se sabía")
        void repetirLaDecisionNoVuelveAAvisar() {
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora(EstadoProtectora.ACTIVA)));
            admin.aprobarProtectora(ID_PROTECTORA, ID_ADMIN);

            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                    .thenReturn(Optional.of(protectora(EstadoProtectora.RECHAZADA)));
            admin.rechazarProtectora(ID_PROTECTORA, "otra vez", ID_ADMIN);

            verifyNoInteractions(notificacionService);
        }

        @Test
        @DisplayName("Una protectora inexistente da 404")
        void protectoraInexistenteDa404() {
            when(protectoraRepository.buscarConCuenta(ID_PROTECTORA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> admin.aprobarProtectora(ID_PROTECTORA, ID_ADMIN))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> admin.rechazarProtectora(ID_PROTECTORA, "x", ID_ADMIN))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Aprobar y rechazar cubren TODOS los estados del enum, sin rama por defecto")
        void losSwitchesCubrenElEnumEntero() {
            // Los dos switches de AdminService son exhaustivos, así que añadir un
            // estado rompe la compilación y esta prueba ni llegaría a ejecutarse.
            // Lo que cubre es el paso siguiente: que nadie resuelva esa rotura
            // metiendo un "default" que deje el estado sin tocar. Recorre el enum
            // entero, no una lista de estados escrita a mano, para que el día que
            // crezca siga comprobándolos todos.
            for (EstadoProtectora inicial : EstadoProtectora.values()) {
                Protectora aprobada = protectora(inicial);
                when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                        .thenReturn(Optional.of(aprobada));
                admin.aprobarProtectora(ID_PROTECTORA, ID_ADMIN);
                assertThat(aprobada.getEstado())
                        .as("aprobar desde %s debe terminar en ACTIVA", inicial)
                        .isEqualTo(EstadoProtectora.ACTIVA);

                Protectora rechazada = protectora(inicial);
                when(protectoraRepository.buscarConCuenta(ID_PROTECTORA))
                        .thenReturn(Optional.of(rechazada));
                admin.rechazarProtectora(ID_PROTECTORA, "motivo", ID_ADMIN);
                assertThat(rechazada.getEstado())
                        .as("rechazar desde %s debe terminar en RECHAZADA", inicial)
                        .isEqualTo(EstadoProtectora.RECHAZADA);
            }
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Reportes (RF-11, RF-26)")
    class Reportes {

        @Test
        @DisplayName("Pide los PENDIENTE y hoy devuelve vacío: nada puede crear reportes todavía")
        void hoyDevuelveVacio() {
            when(reporteRepository.buscarPorEstadoConAutor(EstadoReporte.PENDIENTE))
                    .thenReturn(List.of());

            assertThat(admin.listarReportesPendientes()).isEmpty();
            verify(reporteRepository).buscarPorEstadoConAutor(EstadoReporte.PENDIENTE);
        }
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Retirada de publicaciones (RF-11)")
    class Retirada {

        @Test
        @DisplayName("Una alerta pasa a ELIMINADA, y la fila sigue ahí")
        void laAlertaSeRetiraSinBorrarse() {
            Alerta alerta = new Alerta();
            alerta.setId(ID_PUBLICACION);
            alerta.setEstado(EstadoAlerta.ACTIVA);
            when(alertaRepository.findById(ID_PUBLICACION)).thenReturn(Optional.of(alerta));

            admin.retirarPublicacion(TipoContenido.ALERTA, ID_PUBLICACION, ID_ADMIN);

            assertThat(alerta.getEstado()).isEqualTo(EstadoAlerta.ELIMINADA);
            verify(alertaRepository, never()).delete(any(Alerta.class));
            verify(alertaRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("Un avistamiento pasa a ELIMINADO, y la fila sigue ahí: de ella cuelgan chats")
        void elAvistamientoSeRetiraSinBorrarse() {
            Avistamiento avistamiento = new Avistamiento();
            avistamiento.setId(ID_PUBLICACION);
            avistamiento.setEstado(EstadoAvistamiento.VISIBLE);
            when(avistamientoRepository.findById(ID_PUBLICACION))
                    .thenReturn(Optional.of(avistamiento));

            admin.retirarPublicacion(TipoContenido.AVISTAMIENTO, ID_PUBLICACION, ID_ADMIN);

            assertThat(avistamiento.getEstado()).isEqualTo(EstadoAvistamiento.ELIMINADO);
            verify(avistamientoRepository, never()).delete(any(Avistamiento.class));
            verify(avistamientoRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("Un animal pasa a RETIRADO, el mismo estado que usa su protectora")
        void elAnimalSeRetiraSinBorrarse() {
            AnimalAdopcion animal = new AnimalAdopcion();
            animal.setId(ID_PUBLICACION);
            animal.setEstado(EstadoAnimal.DISPONIBLE);
            when(animalAdopcionRepository.findById(ID_PUBLICACION)).thenReturn(Optional.of(animal));

            admin.retirarPublicacion(TipoContenido.ADOPCION, ID_PUBLICACION, ID_ADMIN);

            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.RETIRADO);
            verify(animalAdopcionRepository, never()).delete(any(AnimalAdopcion.class));
            verify(animalAdopcionRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("El tipo elige la tabla: retirar una alerta no toca las otras dos")
        void elTipoEligeLaTabla() {
            Alerta alerta = new Alerta();
            alerta.setId(ID_PUBLICACION);
            alerta.setEstado(EstadoAlerta.ACTIVA);
            when(alertaRepository.findById(ID_PUBLICACION)).thenReturn(Optional.of(alerta));

            admin.retirarPublicacion(TipoContenido.ALERTA, ID_PUBLICACION, ID_ADMIN);

            // El mismo id existe en las tres tablas; sin el discriminador esto
            // no podría distinguirse.
            verifyNoInteractions(avistamientoRepository, animalAdopcionRepository);
        }

        @Test
        @DisplayName("Retirar lo ya retirado no cambia nada, en los tres tipos")
        void retirarEsIdempotente() {
            Alerta alerta = new Alerta();
            alerta.setEstado(EstadoAlerta.ELIMINADA);
            Avistamiento avistamiento = new Avistamiento();
            avistamiento.setEstado(EstadoAvistamiento.ELIMINADO);
            AnimalAdopcion animal = new AnimalAdopcion();
            animal.setEstado(EstadoAnimal.RETIRADO);

            when(alertaRepository.findById(ID_PUBLICACION)).thenReturn(Optional.of(alerta));
            when(avistamientoRepository.findById(ID_PUBLICACION))
                    .thenReturn(Optional.of(avistamiento));
            when(animalAdopcionRepository.findById(ID_PUBLICACION)).thenReturn(Optional.of(animal));

            for (TipoContenido tipo : TipoContenido.values()) {
                admin.retirarPublicacion(tipo, ID_PUBLICACION, ID_ADMIN);
            }

            assertThat(alerta.getEstado()).isEqualTo(EstadoAlerta.ELIMINADA);
            assertThat(avistamiento.getEstado()).isEqualTo(EstadoAvistamiento.ELIMINADO);
            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.RETIRADO);
        }

        @Test
        @DisplayName("Los TRES tipos del enum se atienden: ninguno cae en un silencio")
        void elSwitchCubreElEnumEntero() {
            // Mismo razonamiento que el de los estados de protectora: el switch de
            // retirarPublicacion es exhaustivo y sin rama por defecto, y esto
            // impide que se "arregle" con un default que no haga nada. Recorriendo
            // el enum, un TipoContenido nuevo sin tratar falla aquí con 404 en vez
            // de responder 204 sin haber retirado nada.
            for (TipoContenido tipo : TipoContenido.values()) {
                when(alertaRepository.findById(anyLong())).thenReturn(Optional.empty());
                when(avistamientoRepository.findById(anyLong())).thenReturn(Optional.empty());
                when(animalAdopcionRepository.findById(anyLong())).thenReturn(Optional.empty());

                assertThatThrownBy(() -> admin.retirarPublicacion(tipo, ID_PUBLICACION, ID_ADMIN))
                        .as("el tipo %s debe buscar su publicación, no ignorarse", tipo)
                        .isInstanceOf(ResourceNotFoundException.class);
            }
        }

        @Test
        @DisplayName("Una publicación inexistente da 404 en cualquiera de los tres tipos")
        void publicacionInexistenteDa404() {
            when(alertaRepository.findById(anyLong())).thenReturn(Optional.empty());
            when(avistamientoRepository.findById(anyLong())).thenReturn(Optional.empty());
            when(animalAdopcionRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> admin.retirarPublicacion(
                    TipoContenido.ALERTA, ID_PUBLICACION, ID_ADMIN))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> admin.retirarPublicacion(
                    TipoContenido.AVISTAMIENTO, ID_PUBLICACION, ID_ADMIN))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> admin.retirarPublicacion(
                    TipoContenido.ADOPCION, ID_PUBLICACION, ID_ADMIN))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Retirar una publicación no notifica a nadie: el aviso es RF-26, no RF-11")
        void retirarNoNotifica() {
            Alerta alerta = new Alerta();
            alerta.setEstado(EstadoAlerta.ACTIVA);
            when(alertaRepository.findById(ID_PUBLICACION)).thenReturn(Optional.of(alerta));

            admin.retirarPublicacion(TipoContenido.ALERTA, ID_PUBLICACION, ID_ADMIN);

            // REPORTE_RESUELTO es del bloque 9 y se manda al que reportó, no al
            // autor de la publicación. Aquí no hay reporte del que colgarlo.
            verifyNoInteractions(notificacionService);
        }
    }

    // ---------------------------------------------------------------------

    private static Usuario usuario(Long id, Rol rol, boolean activo) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setNombre("Usuario " + id);
        usuario.setEmail("usuario" + id + "@test.es");
        usuario.setRol(rol);
        usuario.setActivo(activo);
        usuario.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        return usuario;
    }

    private static Protectora protectora(EstadoProtectora estado) {
        Protectora protectora = new Protectora();
        protectora.setId(ID_PROTECTORA);
        protectora.setNombreOrganizacion("Huellas");
        protectora.setDireccion("C/ Mayor 1");
        protectora.setZonaGeografica("Madrid");
        protectora.setEstado(estado);
        protectora.setUsuario(usuario(30L, Rol.PROTECTORA, true));
        protectora.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        return protectora;
    }
}
