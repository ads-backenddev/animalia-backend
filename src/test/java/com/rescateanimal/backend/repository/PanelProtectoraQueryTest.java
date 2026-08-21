package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.MatchRegistro;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.Tamanyo;
import com.rescateanimal.backend.model.enums.TipoPublicacion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las consultas del panel de la protectora contra PostgreSQL de verdad (RF-10).
 *
 * Existe por lo mismo que CatalogoAdopcionQueryTest: ProtectoraPanelServiceTest
 * simula los repositorios, así que comprueba que el service pide lo correcto
 * pero no que las consultas filtren lo que prometen —ni siquiera que su JPQL sea
 * válido—. Y aquí hay dos filtros que solo se pueden verificar ejecutándolos:
 * que los descartes no cuenten como solicitudes, y que un match sobre un
 * avistamiento con el mismo id no se cuele como solicitud de adopción.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d"),
 * como BackendApplicationTests y CatalogoAdopcionQueryTest.
 */
@ActiveProfiles("test")
@SpringBootTest(properties =
        "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890")
class PanelProtectoraQueryTest {

    /** Marca de esta ejecución, para no chocar con datos de otras pruebas. */
    private final String marca = UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private AnimalAdopcionRepository animalAdopcionRepository;
    @Autowired
    private MatchRegistroRepository matchRegistroRepository;
    @Autowired
    private ProtectoraRepository protectoraRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private com.rescateanimal.backend.service.ProtectoraPanelService protectoraPanelService;

    private Protectora protectora;
    private Protectora otraProtectora;
    private Usuario ciudadano;

    private final Map<String, Long> animales = new java.util.HashMap<>();
    private final List<Long> matchesCreados = new ArrayList<>();

    @BeforeEach
    void sembrar() {
        protectora = crearProtectora("Nuestra");
        otraProtectora = crearProtectora("Ajena");
        ciudadano = crearCiudadano();

        crear(protectora, "Luna", EstadoAnimal.DISPONIBLE);
        crear(protectora, "Rocky", EstadoAnimal.ADOPTADO);
        crear(protectora, "Tina", EstadoAnimal.RETIRADO);
        crear(otraProtectora, "Ajeno", EstadoAnimal.DISPONIBLE);
    }

    @AfterEach
    void limpiar() {
        matchRegistroRepository.deleteAllById(matchesCreados);
        animalAdopcionRepository.deleteAllById(animales.values());
        usuarioRepository.delete(ciudadano);
        protectoraRepository.delete(protectora);
        protectoraRepository.delete(otraProtectora);
        usuarioRepository.delete(protectora.getUsuario());
        usuarioRepository.delete(otraProtectora.getUsuario());
    }

    // ---------------------------------------------------------------------
    // Catálogo del panel frente al catálogo público
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("El panel enseña los RETIRADO; el catálogo público, no")
    void elPanelIncluyeLosRetirados() {
        // Es la diferencia deliberada entre los dos listados: el catálogo es un
        // escaparate y solo ofrece los DISPONIBLE, mientras que el panel es el
        // histórico de gestión de la protectora, donde un animal retirado sigue
        // siendo suyo. Tina solo aparece en uno de los dos.
        assertThat(nombresDelPanel(protectora)).contains("Tina");
        assertThat(nombresDelCatalogoPublico()).doesNotContain("Tina");
    }

    @Test
    @DisplayName("El panel enseña los tres estados a la vez")
    void elPanelEnsenaLosTresEstados() {
        assertThat(nombresDelPanel(protectora))
                .containsExactlyInAnyOrder("Luna", "Rocky", "Tina");

        // El público solo el DISPONIBLE de esta protectora.
        assertThat(nombresDelCatalogoPublico()).contains("Luna").doesNotContain("Rocky", "Tina");
    }

    @Test
    @DisplayName("De punta a punta, el service del panel devuelve también los RETIRADO")
    void elServiceDelPanelDevuelveLosRetirados() {
        // Recorre ProtectoraPanelService -> AnimalAdopcionService con los beans
        // reales de Spring, no con simulaciones. Es el único sitio donde se
        // ejercita esa llamada entre servicios: comprueba a la vez que el
        // método sigue siendo alcanzable a través del proxy y que la política
        // del panel —los tres estados— llega intacta hasta el DTO.
        PaginaResponse<AnimalAdopcionResponse> pagina = protectoraPanelService.animales(
                protectora.getUsuario().getId(), PageRequest.of(0, 50));

        assertThat(pagina.getContent()).extracting(AnimalAdopcionResponse::getNombre)
                .containsExactlyInAnyOrder("Luna", "Rocky", "Tina");
        assertThat(pagina.getContent()).extracting(AnimalAdopcionResponse::getEstado)
                .contains("RETIRADO");
    }

    @Test
    @DisplayName("De punta a punta, una protectora PENDIENTE no abre el panel")
    void elServiceDelPanelCierraALaPendiente() {
        Protectora pendiente = crearProtectora("Pendiente");
        pendiente.setEstado(EstadoProtectora.PENDIENTE);
        protectoraRepository.save(pendiente);

        try {
            assertThatThrownBy(() -> protectoraPanelService.resumen(
                    pendiente.getUsuario().getId()))
                    .isInstanceOf(CuentaNoAutorizadaException.class)
                    .satisfies(e -> assertThat(((CuentaNoAutorizadaException) e).getCodigo())
                            .isEqualTo("PROTECTORA_PENDIENTE"));
        } finally {
            protectoraRepository.delete(pendiente);
            usuarioRepository.delete(pendiente.getUsuario());
        }
    }

    @Test
    @DisplayName("El panel de una protectora nunca incluye animales de otra")
    void elPanelNoMezclaProtectoras() {
        assertThat(nombresDelPanel(protectora)).doesNotContain("Ajeno");
        assertThat(nombresDelPanel(otraProtectora)).containsExactly("Ajeno");
    }

    // ---------------------------------------------------------------------
    // Solicitudes recibidas
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Un descarte nunca aparece como solicitud")
    void elDescarteNoEsSolicitud() {
        deslizar(ciudadano, idDe("Luna"), TipoPublicacion.ADOPCION, true);
        deslizar(ciudadano, idDe("Rocky"), TipoPublicacion.ADOPCION, false);

        List<MatchRegistro> solicitudes = solicitudesDe(protectora);

        // Los dos comparten tabla; solo el primero es una solicitud. Contar el
        // descarte le diría a la protectora que alguien quiere a Rocky cuando
        // en realidad dijo que no.
        assertThat(solicitudes).hasSize(1);
        assertThat(solicitudes.get(0).getPublicacionId()).isEqualTo(idDe("Luna"));
        assertThat(solicitudes.get(0).getInteresado()).isTrue();
    }

    @Test
    @DisplayName("Un match sobre un avistamiento con el mismo id no se cuela como solicitud")
    void elTipoDistingueLaPublicacion() {
        // publicacion_id es una referencia polimórfica: el id de Luna existe
        // también como avistamiento. Sin emparejar el id con el tipo, este
        // deslizamiento aparecería como una solicitud de adopción sobre Luna.
        deslizar(ciudadano, idDe("Luna"), TipoPublicacion.AVISTAMIENTO, true);

        assertThat(solicitudesDe(protectora)).isEmpty();
    }

    @Test
    @DisplayName("Las solicitudes sobre animales de otra protectora no aparecen")
    void lasSolicitudesNoMezclanProtectoras() {
        deslizar(ciudadano, idDe("Luna"), TipoPublicacion.ADOPCION, true);
        deslizar(ciudadano, idDe("Ajeno"), TipoPublicacion.ADOPCION, true);

        assertThat(solicitudesDe(protectora)).hasSize(1);
        assertThat(solicitudesDe(protectora).get(0).getPublicacionId())
                .isEqualTo(idDe("Luna"));

        assertThat(solicitudesDe(otraProtectora)).hasSize(1);
        assertThat(solicitudesDe(otraProtectora).get(0).getPublicacionId())
                .isEqualTo(idDe("Ajeno"));
    }

    @Test
    @DisplayName("Una solicitud sobre un animal ya adoptado sigue apareciendo en el listado")
    void laSolicitudSobreUnAdoptadoSigueSaliendo() {
        deslizar(ciudadano, idDe("Rocky"), TipoPublicacion.ADOPCION, true);

        // El listado es el histórico completo: el chat sigue vivo y la
        // protectora tiene que poder verlo. Lo que cambia con el estado del
        // animal es el contador de pendientes, no el listado.
        assertThat(solicitudesDe(protectora)).hasSize(1);
    }

    @Test
    @DisplayName("La solicitud trae ya cargado al interesado, sin consulta extra")
    void laSolicitudTraeAlInteresado() {
        deslizar(ciudadano, idDe("Luna"), TipoPublicacion.ADOPCION, true);

        MatchRegistro solicitud = solicitudesDe(protectora).get(0);

        assertThat(solicitud.getUsuario().getNombre()).isEqualTo(ciudadano.getNombre());
    }

    // ---------------------------------------------------------------------
    // Contadores del resumen
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("El recuento agrupa por estado y solo devuelve los que existen")
    void elRecuentoAgrupaPorEstado() {
        Map<EstadoAnimal, Long> porEstado = conteoDe(protectora);

        assertThat(porEstado).containsOnlyKeys(
                EstadoAnimal.DISPONIBLE, EstadoAnimal.ADOPTADO, EstadoAnimal.RETIRADO);
        assertThat(porEstado.values()).allMatch(total -> total == 1L);

        // La otra protectora tiene un solo animal, y solo aparece su estado.
        assertThat(conteoDe(otraProtectora)).containsExactly(Map.entry(EstadoAnimal.DISPONIBLE, 1L));
    }

    @Test
    @DisplayName("Pendientes cuenta solo las solicitudes sobre animales DISPONIBLE")
    void pendientesCuentaSoloLasDeAnimalesDisponibles() {
        deslizar(ciudadano, idDe("Luna"), TipoPublicacion.ADOPCION, true);   // DISPONIBLE
        deslizar(ciudadano, idDe("Rocky"), TipoPublicacion.ADOPCION, true);  // ADOPTADO
        deslizar(ciudadano, idDe("Tina"), TipoPublicacion.ADOPCION, true);   // RETIRADO

        assertThat(matchRegistroRepository.contarSolicitudesPendientes(
                protectora.getId(), TipoPublicacion.ADOPCION, EstadoAnimal.DISPONIBLE))
                .isEqualTo(1);

        assertThat(matchRegistroRepository.contarSolicitudesDeProtectora(
                protectora.getId(), TipoPublicacion.ADOPCION))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Los descartes no cuentan tampoco en los contadores")
    void losDescartesNoCuentan() {
        deslizar(ciudadano, idDe("Luna"), TipoPublicacion.ADOPCION, false);

        assertThat(matchRegistroRepository.contarSolicitudesDeProtectora(
                protectora.getId(), TipoPublicacion.ADOPCION)).isZero();
        assertThat(matchRegistroRepository.contarSolicitudesPendientes(
                protectora.getId(), TipoPublicacion.ADOPCION, EstadoAnimal.DISPONIBLE)).isZero();
    }

    @Test
    @DisplayName("Una protectora sin nada devuelve ceros y listas vacías, no errores")
    void laProtectoraVaciaNoRompe() {
        Protectora recienCreada = crearProtectora("Vacia");
        try {
            assertThat(animalAdopcionRepository.contarPorEstado(recienCreada.getId())).isEmpty();
            assertThat(solicitudesDe(recienCreada)).isEmpty();
            assertThat(matchRegistroRepository.contarSolicitudesDeProtectora(
                    recienCreada.getId(), TipoPublicacion.ADOPCION)).isZero();
        } finally {
            protectoraRepository.delete(recienCreada);
            usuarioRepository.delete(recienCreada.getUsuario());
        }
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    private List<MatchRegistro> solicitudesDe(Protectora quien) {
        return matchRegistroRepository
                .buscarSolicitudesDeProtectora(quien.getId(), TipoPublicacion.ADOPCION);
    }

    private Map<EstadoAnimal, Long> conteoDe(Protectora quien) {
        return animalAdopcionRepository.contarPorEstado(quien.getId()).stream()
                .collect(Collectors.toMap(
                        AnimalAdopcionRepository.ConteoPorEstado::getEstado,
                        AnimalAdopcionRepository.ConteoPorEstado::getTotal));
    }

    /** El listado del panel: los de esa protectora, en cualquier estado. */
    private List<String> nombresDelPanel(Protectora quien) {
        return animalAdopcionRepository.findAll(
                        CatalogoAdopcionSpec.deLaProtectora(quien.getId()),
                        PageRequest.of(0, 50, orden()))
                .getContent().stream()
                .map(AnimalAdopcion::getNombre)
                .toList();
    }

    /** El catálogo público, acotado a lo sembrado por esta ejecución. */
    private List<String> nombresDelCatalogoPublico() {
        return animalAdopcionRepository.findAll(
                        CatalogoAdopcionSpec.de(EstadoAnimal.DISPONIBLE, null, null, null),
                        PageRequest.of(0, 200, orden()))
                .getContent().stream()
                .filter(animal -> animales.containsValue(animal.getId()))
                .map(AnimalAdopcion::getNombre)
                .toList();
    }

    private static Sort orden() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    private Long idDe(String nombre) {
        return animales.get(nombre);
    }

    private void deslizar(Usuario quien, Long publicacionId, TipoPublicacion tipo,
                          boolean interesado) {

        MatchRegistro registro = new MatchRegistro();
        registro.setUsuario(quien);
        registro.setPublicacionId(publicacionId);
        registro.setTipoPublicacion(tipo);
        registro.setInteresado(interesado);
        matchesCreados.add(matchRegistroRepository.save(registro).getId());
    }

    private void crear(Protectora duenya, String nombre, EstadoAnimal estado) {
        AnimalAdopcion animal = new AnimalAdopcion();
        animal.setProtectora(duenya);
        animal.setNombre(nombre);
        animal.setEspecie("Perro");
        animal.setTamanyo(Tamanyo.PEQUENO);
        animal.setDescripcion("Sembrado por PanelProtectoraQueryTest " + marca);
        animal.setFotoUrl("https://cdn.test/" + nombre.toLowerCase() + ".jpg");
        animal.setZonaGeografica("Madrid");
        animal.setEstado(estado);

        animales.put(nombre, animalAdopcionRepository.save(animal).getId());
    }

    private Usuario crearCiudadano() {
        Usuario nuevo = new Usuario();
        nuevo.setNombre("Ciudadano " + marca);
        nuevo.setEmail("ciudadano-" + marca + "-" + System.nanoTime() + "@test.es");
        nuevo.setPasswordHash("$2a$10$hashDePrueba");
        nuevo.setRol(Rol.CIUDADANO);
        nuevo.setActivo(true);
        return usuarioRepository.save(nuevo);
    }

    private Protectora crearProtectora(String etiqueta) {
        Usuario cuenta = new Usuario();
        cuenta.setNombre("Protectora " + etiqueta + " " + marca);
        cuenta.setEmail("protectora-" + etiqueta.toLowerCase() + "-" + marca
                + "-" + System.nanoTime() + "@test.es");
        cuenta.setPasswordHash("$2a$10$hashDePrueba");
        cuenta.setRol(Rol.PROTECTORA);
        cuenta.setActivo(true);
        Usuario guardada = usuarioRepository.save(cuenta);

        Protectora nueva = new Protectora();
        nueva.setUsuario(guardada);
        nueva.setNombreOrganizacion("Protectora " + etiqueta + " " + marca);
        nueva.setDireccion("Calle Mayor 1");
        nueva.setZonaGeografica("Madrid");
        nueva.setEstado(EstadoProtectora.ACTIVA);
        return protectoraRepository.save(nueva);
    }
}
