package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.Alerta;
import com.rescateanimal.backend.model.Avistamiento;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAlerta;
import com.rescateanimal.backend.model.enums.EstadoAvistamiento;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UsuarioRepository.buscarParticipantesDeChatsDeLaAlerta contra PostgreSQL de
 * verdad: los destinatarios del aviso ALERTA_ENCONTRADA.
 *
 * Lo que se comprueba es a quién deja fuera, que es donde se equivocaría: los
 * descartes, los avistamientos de otra alerta, un swipe de adopción con el mismo
 * id —la referencia es polimórfica—, las cuentas inactivas y el propio dueño.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d").
 */
@ActiveProfiles("test")
@SpringBootTest
class ParticipantesDeLaAlertaQueryTest {

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private AlertaRepository alertaRepository;
    @Autowired
    private AvistamientoRepository avistamientoRepository;
    @Autowired
    private MatchRegistroRepository matchRegistroRepository;

    private Usuario dueno;
    private Alerta alerta;
    private final List<Usuario> usuarios = new ArrayList<>();
    private final List<Long> alertas = new ArrayList<>();
    private final List<Long> avistamientos = new ArrayList<>();
    private final List<Long> deslizamientos = new ArrayList<>();

    @BeforeEach
    void preparar() {
        dueno = usuario(true);
        alerta = alerta(dueno);
    }

    @AfterEach
    void limpiar() {
        matchRegistroRepository.deleteAllById(deslizamientos);
        avistamientoRepository.deleteAllById(avistamientos);
        alertaRepository.deleteAllById(alertas);
        usuarioRepository.deleteAll(usuarios);
    }

    @Test
    @DisplayName("Los dos extremos de cada chat de la alerta, una vez cada uno, sin el dueño ni las cuentas inactivas")
    void participantes() {
        Usuario conMatch = usuario(true);
        deslizar(dueno, avistamiento(alerta, conMatch), TipoPublicacion.AVISTAMIENTO, true);

        // Dos chats con el mismo testigo: sale una vez.
        Usuario conDosMatches = usuario(true);
        deslizar(dueno, avistamiento(alerta, conDosMatches), TipoPublicacion.AVISTAMIENTO, true);
        deslizar(dueno, avistamiento(alerta, conDosMatches), TipoPublicacion.AVISTAMIENTO, true);

        // Un match de antes de reservar el swipe al dueño: los dos extremos.
        Usuario quienDeslizo = usuario(true);
        Usuario suTestigo = usuario(true);
        deslizar(quienDeslizo, avistamiento(alerta, suTestigo), TipoPublicacion.AVISTAMIENTO, true);

        // Fuera: un descarte, una cuenta inactiva, otra alerta y un swipe de
        // adopción cuyo publicacion_id coincide con un avistamiento de esta.
        Usuario descartado = usuario(true);
        deslizar(dueno, avistamiento(alerta, descartado), TipoPublicacion.AVISTAMIENTO, false);
        Usuario inactivo = usuario(false);
        deslizar(dueno, avistamiento(alerta, inactivo), TipoPublicacion.AVISTAMIENTO, true);
        Usuario deOtraAlerta = usuario(true);
        deslizar(dueno, avistamiento(alerta(dueno), deOtraAlerta), TipoPublicacion.AVISTAMIENTO, true);
        Usuario deAdopcion = usuario(true);
        deslizar(deAdopcion, avistamiento(alerta, usuario(true)), TipoPublicacion.ADOPCION, true);

        List<Long> ids = usuarioRepository
                .buscarParticipantesDeChatsDeLaAlerta(alerta.getId(), dueno.getId(), TipoPublicacion.AVISTAMIENTO)
                .stream().map(Usuario::getId).toList();

        assertThat(ids).containsExactlyInAnyOrder(
                conMatch.getId(), conDosMatches.getId(), quienDeslizo.getId(), suTestigo.getId());
    }

    @Test
    @DisplayName("Sin matches positivos no hay a quién avisar")
    void sinMatches() {
        deslizar(dueno, avistamiento(alerta, usuario(true)), TipoPublicacion.AVISTAMIENTO, false);

        assertThat(usuarioRepository.buscarParticipantesDeChatsDeLaAlerta(
                alerta.getId(), dueno.getId(), TipoPublicacion.AVISTAMIENTO)).isEmpty();
    }

    // ---------------------------------------------------------------------

    private Usuario usuario(boolean activo) {
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Participante");
        nuevo.setEmail("participantes-" + UUID.randomUUID() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(activo);
        Usuario guardado = usuarioRepository.save(nuevo);
        usuarios.add(guardado);
        return guardado;
    }

    private Alerta alerta(Usuario autor) {
        Alerta nueva = new Alerta();
        nueva.setUsuario(autor);
        nueva.setDescripcion("Para los participantes");
        nueva.setEspecie("participantes-" + UUID.randomUUID().toString().substring(0, 8));
        nueva.setFechaAvistamiento(Instant.now());
        nueva.setEstado(EstadoAlerta.ACTIVA);
        Alerta guardada = alertaRepository.save(nueva);
        alertas.add(guardada.getId());
        return guardada;
    }

    private long avistamiento(Alerta deLaAlerta, Usuario autor) {
        Avistamiento nuevo = new Avistamiento();
        nuevo.setAlerta(deLaAlerta);
        nuevo.setUsuario(autor);
        nuevo.setDescripcion("Visto");
        nuevo.setEstado(EstadoAvistamiento.VISIBLE);
        Long id = avistamientoRepository.save(nuevo).getId();
        avistamientos.add(id);
        return id;
    }

    private void deslizar(Usuario quien, long publicacionId, TipoPublicacion tipo, boolean interesado) {
        MatchRegistro registro = new MatchRegistro();
        registro.setUsuario(quien);
        registro.setPublicacionId(publicacionId);
        registro.setTipoPublicacion(tipo);
        registro.setInteresado(interesado);
        deslizamientos.add(matchRegistroRepository.save(registro).getId());
    }
}
