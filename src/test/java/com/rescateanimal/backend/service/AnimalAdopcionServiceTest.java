package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.ActualizarAnimalRequest;
import com.rescateanimal.backend.dto.request.CrearAnimalRequest;
import com.rescateanimal.backend.dto.response.AnimalAdopcionDetalleResponse;
import com.rescateanimal.backend.dto.response.AnimalAdopcionResponse;
import com.rescateanimal.backend.dto.response.PaginaResponse;
import com.rescateanimal.backend.exception.AnimalAjenoException;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.exception.OperacionNoPermitidaException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.model.enums.Tamanyo;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del catálogo de adopción (RF-07, RF-20, RF-22).
 *
 * Dos cosas concentran casi todo lo que puede salir mal aquí: que los filtros
 * combinen de verdad en vez de anularse entre sí, y que una protectora no pueda
 * tocar los animales de otra. El resto son transiciones de estado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnimalAdopcionServiceTest {

    private static final int TAMANYO_FEED = 30;
    private static final Long ID_ANIMAL = 33L;
    private static final Long ID_PROTECTORA = 70L;

    /** Cuenta de la protectora dueña del animal. */
    private static final Long ID_DUENYA = 20L;
    /** Cuenta de otra protectora distinta. */
    private static final Long ID_OTRA_PROTECTORA = 21L;

    @Mock
    private AnimalAdopcionRepository animalAdopcionRepository;
    @Mock
    private ProtectoraRepository protectoraRepository;

    private AnimalAdopcionService animalAdopcionService;

    @BeforeEach
    void prepararServicio() {
        animalAdopcionService =
                new AnimalAdopcionService(animalAdopcionRepository, protectoraRepository, TAMANYO_FEED);

        when(protectoraRepository.findByUsuarioId(ID_DUENYA)).thenReturn(Optional.of(protectora()));
        when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                .thenReturn(Optional.of(animal(EstadoAnimal.DISPONIBLE)));
        when(animalAdopcionRepository.save(any())).thenAnswer(invocacion -> {
            AnimalAdopcion guardado = invocacion.getArgument(0);
            guardado.setId(ID_ANIMAL);
            guardado.setCreatedAt(Instant.now());
            return guardado;
        });
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Publicación (RF-07)")
    class Publicacion {

        @Test
        @DisplayName("Un animal nuevo nace DISPONIBLE y a nombre de la protectora del token")
        void naceDisponibleYDeSuProtectora() {
            AnimalAdopcionResponse respuesta =
                    animalAdopcionService.publicar(peticionDeAlta(), ID_DUENYA);

            ArgumentCaptor<AnimalAdopcion> guardado = ArgumentCaptor.forClass(AnimalAdopcion.class);
            verify(animalAdopcionRepository).save(guardado.capture());

            assertThat(guardado.getValue().getEstado()).isEqualTo(EstadoAnimal.DISPONIBLE);
            assertThat(guardado.getValue().getProtectora().getId()).isEqualTo(ID_PROTECTORA);
            assertThat(respuesta.getEstado()).isEqualTo("DISPONIBLE");
        }

        @Test
        @DisplayName("Los textos se guardan sin espacios sobrantes")
        void seRecortanLosTextos() {
            CrearAnimalRequest peticion = peticionDeAlta();
            peticion.setNombre("  Luna  ");
            peticion.setZonaGeografica("  Madrid  ");

            animalAdopcionService.publicar(peticion, ID_DUENYA);

            ArgumentCaptor<AnimalAdopcion> guardado = ArgumentCaptor.forClass(AnimalAdopcion.class);
            verify(animalAdopcionRepository).save(guardado.capture());
            assertThat(guardado.getValue().getNombre()).isEqualTo("Luna");
            assertThat(guardado.getValue().getZonaGeografica()).isEqualTo("Madrid");
        }

        @Test
        @DisplayName("Cuenta con rol PROTECTORA pero sin ficha de protectora -> 403, no 500")
        void sinFichaDeProtectoraDevuelve403() {
            when(protectoraRepository.findByUsuarioId(ID_DUENYA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> animalAdopcionService.publicar(peticionDeAlta(), ID_DUENYA))
                    .isInstanceOf(CuentaNoAutorizadaException.class)
                    .satisfies(e -> assertThat(((CuentaNoAutorizadaException) e).getCodigo())
                            .isEqualTo("SIN_PROTECTORA"));

            verify(animalAdopcionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Propiedad del animal")
    class Propiedad {

        @Test
        @DisplayName("Editar un animal de otra protectora -> 403 ANIMAL_AJENO")
        void editarAjenoDevuelve403() {
            assertThatThrownBy(() -> animalAdopcionService.actualizar(
                    ID_ANIMAL, peticionDeEdicion(), ID_OTRA_PROTECTORA))
                    .isInstanceOf(AnimalAjenoException.class)
                    .hasMessageContaining("otra protectora");
        }

        @Test
        @DisplayName("Retirar un animal de otra protectora -> 403 y el estado no cambia")
        void retirarAjenoDevuelve403() {
            AnimalAdopcion animal = animal(EstadoAnimal.DISPONIBLE);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            assertThatThrownBy(() ->
                    animalAdopcionService.retirar(ID_ANIMAL, ID_OTRA_PROTECTORA))
                    .isInstanceOf(AnimalAjenoException.class);

            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.DISPONIBLE);
            verify(animalAdopcionRepository, never()).delete(any(AnimalAdopcion.class));
        }

        @Test
        @DisplayName("Confirmar la adopción de un animal ajeno -> 403 y el estado no cambia")
        void confirmarAjenoDevuelve403() {
            AnimalAdopcion animal = animal(EstadoAnimal.DISPONIBLE);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            assertThatThrownBy(() ->
                    animalAdopcionService.confirmarAdopcion(ID_ANIMAL, ID_OTRA_PROTECTORA))
                    .isInstanceOf(AnimalAjenoException.class);

            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.DISPONIBLE);
        }

        @Test
        @DisplayName("Animal inexistente -> 404, distinguible del 403 del ajeno")
        void animalInexistenteDevuelve404() {
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    animalAdopcionService.actualizar(ID_ANIMAL, peticionDeEdicion(), ID_DUENYA))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("La dueña sí puede con las tres operaciones")
        void laDuenyaPuede() {
            AnimalAdopcion animal = animal(EstadoAnimal.DISPONIBLE);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            assertThatCode(() -> {
                animalAdopcionService.actualizar(ID_ANIMAL, peticionDeEdicion(), ID_DUENYA);
                animalAdopcionService.confirmarAdopcion(ID_ANIMAL, ID_DUENYA);
                animalAdopcionService.retirar(ID_ANIMAL, ID_DUENYA);
            }).doesNotThrowAnyException();

            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.RETIRADO);
        }
    }

    @Nested
    @DisplayName("Retirada del catálogo (RF-07)")
    class Retirada {

        @Test
        @DisplayName("El animal pasa a RETIRADO y la fila NO se borra")
        void pasaARetiradoSinBorrar() {
            AnimalAdopcion animal = animal(EstadoAnimal.DISPONIBLE);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            animalAdopcionService.retirar(ID_ANIMAL, ID_DUENYA);

            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.RETIRADO);
            // Lo que de verdad protege esta prueba: borrar la fila dejaba
            // huérfanos los matches que apuntaran al animal, y con ellos el chat
            // y los mensajes de una conversación en curso.
            verify(animalAdopcionRepository, never()).delete(any(AnimalAdopcion.class));
        }

        @Test
        @DisplayName("Retirarlo dos veces deja el mismo estado: es idempotente")
        void esIdempotente() {
            AnimalAdopcion animal = animal(EstadoAnimal.RETIRADO);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            assertThatCode(() -> animalAdopcionService.retirar(ID_ANIMAL, ID_DUENYA))
                    .doesNotThrowAnyException();

            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.RETIRADO);
        }

        @Test
        @DisplayName("Confirmar la adopción de un animal retirado -> 400 ANIMAL_RETIRADO")
        void confirmarUnRetiradoDevuelve400() {
            AnimalAdopcion animal = animal(EstadoAnimal.RETIRADO);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            assertThatThrownBy(() ->
                    animalAdopcionService.confirmarAdopcion(ID_ANIMAL, ID_DUENYA))
                    .isInstanceOf(OperacionNoPermitidaException.class)
                    .satisfies(e -> assertThat(((OperacionNoPermitidaException) e).getCodigo())
                            .isEqualTo("ANIMAL_RETIRADO"));

            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.RETIRADO);
        }

        @Test
        @DisplayName("Un animal RETIRADO sigue siendo consultable por su detalle")
        void elDetalleSigueRespondiendo() {
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal(EstadoAnimal.RETIRADO)));

            AnimalAdopcionDetalleResponse detalle = animalAdopcionService.detalle(ID_ANIMAL);

            // Quien tenga un chat abierto sobre él merece ver qué pasó con el
            // animal, no un 404.
            assertThat(detalle.getEstado()).isEqualTo("RETIRADO");
        }
    }

    @Nested
    @DisplayName("Confirmación de la adopción (RF-20)")
    class Adopcion {

        @Test
        @DisplayName("El animal pasa a ADOPTADO")
        void pasaAAdoptado() {
            AnimalAdopcion animal = animal(EstadoAnimal.DISPONIBLE);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            AnimalAdopcionResponse respuesta =
                    animalAdopcionService.confirmarAdopcion(ID_ANIMAL, ID_DUENYA);

            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.ADOPTADO);
            assertThat(respuesta.getEstado()).isEqualTo("ADOPTADO");
        }

        @Test
        @DisplayName("Confirmarlo dos veces deja el mismo estado: es idempotente, por eso es PUT")
        void esIdempotente() {
            AnimalAdopcion animal = animal(EstadoAnimal.ADOPTADO);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            AnimalAdopcionResponse respuesta =
                    animalAdopcionService.confirmarAdopcion(ID_ANIMAL, ID_DUENYA);

            assertThat(respuesta.getEstado()).isEqualTo("ADOPTADO");
        }

        @Test
        @DisplayName("La edición no puede devolver un animal adoptado al catálogo")
        void laEdicionNoReabreElAnimal() {
            AnimalAdopcion animal = animal(EstadoAnimal.ADOPTADO);
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal));

            animalAdopcionService.actualizar(ID_ANIMAL, peticionDeEdicion(), ID_DUENYA);

            // El estado no viaja en el cuerpo del PUT: sigue ADOPTADO.
            assertThat(animal.getEstado()).isEqualTo(EstadoAnimal.ADOPTADO);
            assertThat(animal.getNombre()).isEqualTo("Nube");
        }
    }

    /**
     * Lo que el service decide sobre el catálogo.
     *
     * Cómo compara cada filtro ya no se prueba aquí: desde que los criterios
     * viven en CatalogoAdopcionSpec, verificarlos contra un repositorio simulado
     * solo comprobaría que se construye una lambda. Su comportamiento real
     * —combinaciones, mayúsculas, filtro en blanco— se verifica contra
     * PostgreSQL en CatalogoAdopcionQueryTest, que es donde significa algo.
     *
     * Aquí queda lo que sigue siendo responsabilidad del service: que el estado
     * no lo elija el cliente, los topes de página y la forma de la respuesta.
     */
    @Nested
    @DisplayName("Política del catálogo (RF-22)")
    class Catalogo {

        @Test
        @DisplayName("Siempre se consulta con una especificación, sin importar los filtros")
        void siempreSeConsultaConEspecificacion() {
            prepararCatalogo();

            animalAdopcionService.catalogo("perro", Tamanyo.PEQUENO, "Madrid", Pageable.ofSize(20));

            verify(animalAdopcionRepository).findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        @DisplayName("Un size desmedido se recorta al máximo, y el orden lo fija el servidor")
        void elTamanyoSeAcota() {
            prepararCatalogo();

            animalAdopcionService.catalogo(null, null, null, PageRequest.of(0, 5000));

            assertThat(paginacionRecibida().getPageSize())
                    .isEqualTo(AnimalAdopcionService.MAXIMO_POR_PAGINA);
            assertThat(paginacionRecibida().getSort()).isEqualTo(
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        }

        @Test
        @DisplayName("Un size de cero o negativo no revienta: se sube a uno")
        void elTamanyoMinimoEsUno() {
            prepararCatalogo();

            animalAdopcionService.catalogo(null, null, null, Pageable.ofSize(1));

            assertThat(paginacionRecibida().getPageSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("page y size del cliente se respetan dentro del tope")
        void seRespetaLaPaginaPedida() {
            prepararCatalogo();

            animalAdopcionService.catalogo(null, null, null, PageRequest.of(3, 25));

            assertThat(paginacionRecibida().getPageNumber()).isEqualTo(3);
            assertThat(paginacionRecibida().getPageSize()).isEqualTo(25);
        }

        @Test
        @DisplayName("La página sale con la forma documentada en §1")
        void laPaginaConservaLaForma() {
            prepararCatalogo();

            PaginaResponse<AnimalAdopcionResponse> pagina =
                    animalAdopcionService.catalogo(null, null, null, Pageable.ofSize(20));

            assertThat(pagina.getContent()).hasSize(1);
            assertThat(pagina.getContent().get(0).getProtectoraNombre())
                    .isEqualTo("Protectora de prueba");
            assertThat(pagina.getTotalElements()).isEqualTo(1);
            assertThat(pagina.isLast()).isTrue();
        }

        private Pageable paginacionRecibida() {
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(animalAdopcionRepository).findAll(any(Specification.class), captor.capture());
            return captor.getValue();
        }

        private void prepararCatalogo() {
            Page<AnimalAdopcion> pagina = new PageImpl<>(
                    List.of(animal(EstadoAnimal.DISPONIBLE)), PageRequest.of(0, 20), 1);
            when(animalAdopcionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(pagina);
        }
    }


    @Nested
    @DisplayName("Detalle")
    class Detalle {

        @Test
        @DisplayName("Trae los datos públicos de la protectora, sin email ni teléfono (RNF-10)")
        void traeLaProtectoraSinDatosPersonales() {
            assertThat(animalAdopcionService.detalle(ID_ANIMAL))
                    .satisfies(detalle -> {
                        assertThat(detalle.getProtectoraNombre()).isEqualTo("Protectora de prueba");
                        assertThat(detalle.getProtectoraZonaGeografica()).isEqualTo("Madrid");
                    });
        }

        @Test
        @DisplayName("Un animal adoptado sigue teniendo detalle, aunque no salga en el catálogo")
        void elAdoptadoSigueTeniendoDetalle() {
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.of(animal(EstadoAnimal.ADOPTADO)));

            assertThat(animalAdopcionService.detalle(ID_ANIMAL).getEstado()).isEqualTo("ADOPTADO");
        }

        @Test
        @DisplayName("Animal inexistente -> 404")
        void inexistenteDevuelve404() {
            when(animalAdopcionRepository.buscarConProtectora(ID_ANIMAL))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> animalAdopcionService.detalle(ID_ANIMAL))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ---------------------------------------------------------------------

    private static CrearAnimalRequest peticionDeAlta() {
        return new CrearAnimalRequest("Luna", "PERRO", Tamanyo.MEDIANO,
                "Cariñosa y tranquila", "https://cdn.test/luna.jpg", "Madrid");
    }

    private static ActualizarAnimalRequest peticionDeEdicion() {
        return new ActualizarAnimalRequest("Nube", "GATO", Tamanyo.PEQUENO,
                "Muy sociable", "https://cdn.test/nube.jpg", "Sevilla");
    }

    private static AnimalAdopcion animal(EstadoAnimal estado) {
        AnimalAdopcion animal = new AnimalAdopcion();
        animal.setId(ID_ANIMAL);
        animal.setProtectora(protectora());
        animal.setNombre("Luna");
        animal.setEspecie("PERRO");
        animal.setTamanyo(Tamanyo.MEDIANO);
        animal.setDescripcion("Cariñosa y tranquila");
        animal.setFotoUrl("https://cdn.test/luna.jpg");
        animal.setZonaGeografica("Madrid");
        animal.setEstado(estado);
        animal.setCreatedAt(Instant.now());
        return animal;
    }

    private static Protectora protectora() {
        Usuario cuenta = new Usuario();
        cuenta.setId(ID_DUENYA);
        cuenta.setNombre("Protectora de prueba");
        cuenta.setEmail("protectora@test.es");
        cuenta.setRol(Rol.PROTECTORA);
        cuenta.setActivo(true);

        Protectora protectora = new Protectora();
        protectora.setId(ID_PROTECTORA);
        protectora.setUsuario(cuenta);
        protectora.setNombreOrganizacion("Protectora de prueba");
        protectora.setDireccion("Calle Mayor 1");
        protectora.setZonaGeografica("Madrid");
        return protectora;
    }
}
