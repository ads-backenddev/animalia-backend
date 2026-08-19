package com.rescateanimal.backend.repository;

import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.Tamanyo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La consulta del catálogo contra PostgreSQL de verdad (RF-22).
 *
 * Existe por lo mismo que MatchFlujoCompletoTest: AnimalAdopcionServiceTest
 * simula el repositorio, así que comprueba que el service pasa los filtros
 * correctos pero no que la consulta funcione —ni siquiera que sea válida—.
 * Aquí se ejecuta de verdad.
 *
 * Lo que más importa confirmar es que los filtros ausentes no enlazan ningún
 * parámetro nulo. La versión anterior de esta consulta usaba el patrón
 * ":x IS NULL OR ..." en JPQL y fallaba aquí con "function lower(bytea) does
 * not exist": PostgreSQL no puede deducir el tipo de un parámetro nulo. Solo
 * se veía ejecutándola de verdad, y solo en el recuento. Lo segundo es que ese
 * recuento filtre lo mismo que la consulta principal.
 *
 * REQUISITOS PARA EJECUTARLA: base de datos levantada ("docker compose up -d"),
 * como BackendApplicationTests y MatchFlujoCompletoTest.
 */
@SpringBootTest(properties =
        "jwt.secret=secreto-solo-para-tests-de-al-menos-32-caracteres-1234567890")
class CatalogoAdopcionQueryTest {

    /** Marca de esta ejecución, para no chocar con datos de otras pruebas. */
    private final String marca = UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private AnimalAdopcionRepository animalAdopcionRepository;
    @Autowired
    private ProtectoraRepository protectoraRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;

    private Protectora protectora;
    private final List<Long> animalesCreados = new java.util.ArrayList<>();

    @BeforeEach
    void sembrarCatalogo() {
        protectora = crearProtectora();

        crear("Luna", "Perro", Tamanyo.PEQUENO, "Madrid, Carabanchel", EstadoAnimal.DISPONIBLE);
        crear("Nube", "Perro", Tamanyo.GRANDE, "Madrid", EstadoAnimal.DISPONIBLE);
        crear("Sol", "Gato", Tamanyo.PEQUENO, "Sevilla", EstadoAnimal.DISPONIBLE);
        crear("Coco", "perro", Tamanyo.PEQUENO, "MADRID centro", EstadoAnimal.DISPONIBLE);
        crear("Rocky", "Perro", Tamanyo.PEQUENO, "Madrid", EstadoAnimal.ADOPTADO);
    }

    @AfterEach
    void limpiar() {
        animalAdopcionRepository.deleteAllById(animalesCreados);
        protectoraRepository.delete(protectora);
        usuarioRepository.delete(protectora.getUsuario());
    }

    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Sin filtros: no se enlaza ningún parámetro nulo y la consulta es válida")
    void sinFiltrosLaConsultaEsValida() {
        Page<AnimalAdopcion> pagina = buscar(null, null, null);

        // Los tres filtros ausentes: el caso que antes rompía el recuento.
        assertThat(mios(pagina)).containsExactlyInAnyOrder("Luna", "Nube", "Sol", "Coco");
    }

    @Test
    @DisplayName("Nunca devuelve los ADOPTADO, aunque encajen con los filtros")
    void elAdoptadoNuncaSale() {
        assertThat(mios(buscar("Perro", Tamanyo.PEQUENO, "Madrid")))
                .doesNotContain("Rocky");
    }

    @Test
    @DisplayName("Los tres filtros combinados se intersecan de verdad")
    void losTresFiltrosSeIntersecan() {
        Page<AnimalAdopcion> pagina = buscar("Perro", Tamanyo.PEQUENO, "Madrid");

        // Luna: perro pequeño en Madrid, Carabanchel -> entra.
        // Coco: "perro" en minúsculas y "MADRID centro" -> entra, la comparación
        //       no distingue mayúsculas y la zona busca por contenido.
        // Nube: perro en Madrid pero GRANDE -> fuera por tamaño.
        // Sol:  pequeño pero gato y en Sevilla -> fuera por especie y zona.
        // Rocky: encaja en todo menos en el estado -> fuera por ADOPTADO.
        assertThat(mios(pagina)).containsExactlyInAnyOrder("Luna", "Coco");
    }

    @Test
    @DisplayName("Cada filtro por separado recorta lo suyo")
    void cadaFiltroPorSeparado() {
        assertThat(mios(buscar("Gato", null, null))).containsExactly("Sol");
        assertThat(mios(buscar(null, Tamanyo.GRANDE, null))).containsExactly("Nube");
        assertThat(mios(buscar(null, null, "Sevilla"))).containsExactly("Sol");
    }

    @Test
    @DisplayName("Combinaciones parciales: dos filtros sí y uno no")
    void combinacionesParciales() {
        assertThat(mios(buscar("Perro", null, "Madrid")))
                .containsExactlyInAnyOrder("Luna", "Nube", "Coco");
        assertThat(mios(buscar(null, Tamanyo.PEQUENO, "Madrid")))
                .containsExactlyInAnyOrder("Luna", "Coco");
        assertThat(mios(buscar("Perro", Tamanyo.GRANDE, null))).containsExactly("Nube");
    }

    @Test
    @DisplayName("La especie compara sin distinguir mayúsculas")
    void laEspecieIgnoraMayusculas() {
        assertThat(mios(buscar("PERRO", null, null)))
                .containsExactlyInAnyOrder("Luna", "Nube", "Coco");
        assertThat(mios(buscar("perro", null, null)))
                .containsExactlyInAnyOrder("Luna", "Nube", "Coco");
    }

    @Test
    @DisplayName("La zona busca por contenido: Madrid encuentra 'Madrid, Carabanchel'")
    void laZonaBuscaPorContenido() {
        assertThat(mios(buscar(null, null, "madrid")))
                .containsExactlyInAnyOrder("Luna", "Nube", "Coco");
    }

    @Test
    @DisplayName("Un filtro sin coincidencias devuelve página vacía, no error")
    void sinCoincidenciasDevuelveVacio() {
        assertThat(buscar("Hurón", Tamanyo.GRANDE, "Teruel").getContent()).isEmpty();
    }

    @Test
    @DisplayName("El recuento cuenta lo mismo que filtra la consulta principal")
    void elRecuentoCoincideConLoFiltrado() {
        // Página de tamaño 1: el contenido se recorta, el total no. Si la
        // el recuento no aplicara los mismos filtros, este total no cuadraría.
        Page<AnimalAdopcion> primera = animalAdopcionRepository.findAll(
                CatalogoAdopcionSpec.de(EstadoAnimal.DISPONIBLE, "Perro", Tamanyo.PEQUENO, "Madrid"),
                PageRequest.of(0, 1, orden()));

        Page<AnimalAdopcion> completa = buscar("Perro", Tamanyo.PEQUENO, "Madrid");

        assertThat(primera.getContent()).hasSize(1);
        assertThat(primera.getTotalElements()).isEqualTo(completa.getTotalElements());
        assertThat(primera.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("El grafo de la protectora no multiplica filas al paginar")
    void elFetchNoMultiplicaFilas() {
        Page<AnimalAdopcion> pagina = animalAdopcionRepository.findAll(
                CatalogoAdopcionSpec.de(EstadoAnimal.DISPONIBLE, null, null, null),
                PageRequest.of(0, 2, orden()));

        // Es una asociación a-uno: cada fila sigue siendo un animal, así que el
        // LIMIT del SQL cuenta lo mismo que la página.
        assertThat(pagina.getContent()).hasSize(2);
        assertThat(pagina.getContent())
                .allSatisfy(animal ->
                        assertThat(animal.getProtectora().getNombreOrganizacion()).isNotNull());
    }

    @Test
    @DisplayName("buscarConProtectoraPorIds trae la cuenta de la protectora, no un proxy vacío")
    void elLotePorIdsTraeLaCuenta() {
        List<AnimalAdopcion> animales =
                animalAdopcionRepository.buscarConProtectoraPorIds(animalesCreados);

        assertThat(animales).hasSize(animalesCreados.size());
        assertThat(animales).allSatisfy(animal ->
                assertThat(animal.getProtectora().getUsuario().getId()).isNotNull());
    }

    // ---------------------------------------------------------------------

    private Page<AnimalAdopcion> buscar(String especie, Tamanyo tamanyo, String zona) {
        return animalAdopcionRepository.findAll(
                CatalogoAdopcionSpec.de(EstadoAnimal.DISPONIBLE, especie, tamanyo, zona),
                PageRequest.of(0, 50, orden()));
    }

    /** El mismo orden que fija AnimalAdopcionService. */
    private static Sort orden() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    /**
     * Solo los animales sembrados por esta ejecución: la base de datos es la de
     * desarrollo y puede tener datos de otras pruebas o de uso manual.
     */
    private List<String> mios(Page<AnimalAdopcion> pagina) {
        return pagina.getContent().stream()
                .filter(animal -> animalesCreados.contains(animal.getId()))
                .map(AnimalAdopcion::getNombre)
                .toList();
    }

    private void crear(String nombre, String especie, Tamanyo tamanyo, String zona,
                       EstadoAnimal estado) {

        AnimalAdopcion animal = new AnimalAdopcion();
        animal.setProtectora(protectora);
        animal.setNombre(nombre);
        animal.setEspecie(especie);
        animal.setTamanyo(tamanyo);
        animal.setDescripcion("Sembrado por CatalogoAdopcionQueryTest " + marca);
        animal.setFotoUrl("https://cdn.test/" + nombre.toLowerCase() + ".jpg");
        animal.setZonaGeografica(zona);
        animal.setEstado(estado);

        animalesCreados.add(animalAdopcionRepository.save(animal).getId());
    }

    private Protectora crearProtectora() {
        Usuario cuenta = new Usuario();
        cuenta.setNombre("Protectora " + marca);
        cuenta.setEmail("protectora-" + marca + "@test.es");
        cuenta.setPasswordHash("$2a$10$hashDePrueba");
        cuenta.setRol(Rol.PROTECTORA);
        cuenta.setActivo(true);
        Usuario guardada = usuarioRepository.save(cuenta);

        Protectora nueva = new Protectora();
        nueva.setUsuario(guardada);
        nueva.setNombreOrganizacion("Protectora " + marca);
        nueva.setDireccion("Calle Mayor 1");
        nueva.setZonaGeografica("Madrid");
        nueva.setEstado(EstadoProtectora.ACTIVA);
        return protectoraRepository.save(nueva);
    }
}
