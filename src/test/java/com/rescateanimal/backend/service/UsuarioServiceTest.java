package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.ActualizarPerfilRequest;
import com.rescateanimal.backend.dto.response.UsuarioResponse;
import com.rescateanimal.backend.exception.CredencialesInvalidasException;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Perfil propio y baja de cuenta (RF-02, RF-18, RF-19).
 *
 * EL GRUPO QUE MÁS IMPORTA ES EL DE LA BAJA, y no por el camino feliz sino por lo
 * que tiene que seguir en pie después: la fila del usuario, para que las
 * conversaciones ajenas no se queden a medias, y a la vez ni un dato personal
 * dentro de ella. Son dos exigencias que tiran en direcciones opuestas y lo que
 * se prueba aquí es que se cumplen las dos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsuarioServiceTest {

    private static final Long ID_USUARIO = 9L;
    private static final Long ID_PROTECTORA = 70L;
    private static final String EMAIL = "ana@test.es";
    private static final String CLAVE = "passw0rd123";

    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private ProtectoraRepository protectoraRepository;
    @Mock
    private AnimalAdopcionService animalAdopcionService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UsuarioService usuarioService;
    private Usuario usuario;

    @BeforeEach
    void prepararServicio() {
        // AnonimizacionService va de verdad, no simulado: lo que hay que
        // demostrar es que el email resultante es irreversible y determinista, y
        // con un mock eso solo diría que se llama a quien hay que llamar.
        usuarioService = new UsuarioService(usuarioRepository, protectoraRepository,
                animalAdopcionService, passwordEncoder, new AnonimizacionService());

        usuario = ciudadano();
        when(usuarioRepository.findById(ID_USUARIO)).thenReturn(Optional.of(usuario));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
    }

    @Nested
    @DisplayName("Perfil (RF-02)")
    class Perfil {

        @Test
        @DisplayName("Los cuatro campos se actualizan y el email no se toca")
        void seActualizanLosCuatroCampos() {
            UsuarioResponse respuesta = usuarioService.actualizarPerfil(ID_USUARIO,
                    new ActualizarPerfilRequest("Ana María", "600111222", "Getafe",
                            "https://example.test/ana.jpg"));

            assertThat(respuesta.getNombre()).isEqualTo("Ana María");
            assertThat(respuesta.getTelefono()).isEqualTo("600111222");
            assertThat(respuesta.getAreaGeografica()).isEqualTo("Getafe");
            assertThat(respuesta.getFotoUrl()).isEqualTo("https://example.test/ana.jpg");

            // RF-02 no menciona el email, y cambiarlo aquí permitiría quedarse con
            // una cuenta ajena desde una sesión olvidada.
            assertThat(respuesta.getEmail()).isEqualTo(EMAIL);
            assertThat(usuario.getPasswordHash()).isEqualTo("$2a$12$hash");
        }

        @Test
        @DisplayName("Los opcionales vacíos se guardan como ausentes, no como cadena vacía")
        void loVacioSeGuardaComoAusente() {
            UsuarioResponse respuesta = usuarioService.actualizarPerfil(ID_USUARIO,
                    new ActualizarPerfilRequest("  Ana  ", "   ", "", null));

            assertThat(respuesta.getNombre())
                    .as("el nombre se recorta: acaba a la vista de otros")
                    .isEqualTo("Ana");
            // Tener dos formas de decir "no hay teléfono" obliga a comprobar las
            // dos en todas partes.
            assertThat(respuesta.getTelefono()).isNull();
            assertThat(respuesta.getAreaGeografica()).isNull();
            assertThat(respuesta.getFotoUrl()).isNull();
        }

        @Test
        @DisplayName("El perfil se lee del usuario del token, sin recibir ningún id")
        void elPerfilEsSiempreElPropio() {
            assertThat(usuarioService.perfil(ID_USUARIO).getId()).isEqualTo(ID_USUARIO);
            verify(usuarioRepository).findById(ID_USUARIO);
        }
    }

    @Nested
    @DisplayName("Idioma (RF-18)")
    class IdiomaDeLaAplicacion {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"es", "en", "ca", "gl", "eu"})
        @DisplayName("Los cinco idiomas admitidos se aceptan")
        void losAdmitidosSeAceptan(String codigo) {
            assertThat(usuarioService.cambiarIdioma(ID_USUARIO, codigo).getIdioma())
                    .isEqualTo(codigo);
        }

        @ParameterizedTest(name = "{0} -> 400")
        @ValueSource(strings = {"klingon", "fr", "espanol", "e", "zz", "  "})
        @DisplayName("Cualquier otro se rechaza, y el mensaje dice cuáles valen")
        void losNoAdmitidosSeRechazan(String codigo) {
            assertThatThrownBy(() -> usuarioService.cambiarIdioma(ID_USUARIO, codigo))
                    .isInstanceOf(ParametrosInvalidosException.class)
                    .hasMessageContaining("es, en, ca, gl, eu");

            assertThat(usuario.getIdioma())
                    .as("un idioma rechazado no deja rastro")
                    .isEqualTo("es");
        }

        @Test
        @DisplayName("Se guarda el código canónico: ES y es son el mismo idioma")
        void seGuardaElCodigoCanonico() {
            assertThat(usuarioService.cambiarIdioma(ID_USUARIO, "  EU  ").getIdioma())
                    .isEqualTo("eu");
        }

        @Test
        @DisplayName("Un idioma nulo se rechaza sin romper")
        void elNuloSeRechaza() {
            assertThatThrownBy(() -> usuarioService.cambiarIdioma(ID_USUARIO, null))
                    .isInstanceOf(ParametrosInvalidosException.class);
        }
    }

    @Nested
    @DisplayName("Baja de la cuenta (RF-19)")
    class Baja {

        @Test
        @DisplayName("Sin la contraseña correcta no se borra nada")
        void sinLaClaveCorrectaNoSeBorra() {
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> usuarioService.eliminarCuenta(ID_USUARIO, "otra-clave"))
                    .isInstanceOf(CredencialesInvalidasException.class);

            // Ni un campo tocado: el token por sí solo no basta para lo
            // irreversible.
            assertThat(usuario.getNombre()).isEqualTo("Ana");
            assertThat(usuario.getEmail()).isEqualTo(EMAIL);
            assertThat(usuario.getActivo()).isTrue();
        }

        @Test
        @DisplayName("Con la contraseña correcta desaparece todo dato personal")
        void laBajaBorraLosDatosPersonales() {
            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            assertThat(usuario.getNombre()).isEqualTo("Usuario eliminado");
            assertThat(usuario.getTelefono()).isNull();
            assertThat(usuario.getFotoUrl()).isNull();
            assertThat(usuario.getEmail())
                    .as("el email original no puede quedar en ninguna parte")
                    .doesNotContain("ana")
                    .doesNotContain("test.es");
        }

        @Test
        @DisplayName("La fila sigue ahí: el histórico de la otra parte no se toca")
        void laFilaSeConserva() {
            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            // Un DELETE en cascada habría arrastrado las nueve tablas que apuntan
            // aquí, y con ellas la mitad de conversaciones ajenas.
            verify(usuarioRepository, never()).delete(any(Usuario.class));
            verify(usuarioRepository, never()).deleteById(any());
            assertThat(usuario.getId()).isEqualTo(ID_USUARIO);
            assertThat(usuario.getRol())
                    .as("el rol se conserva: no es un dato personal y explica el histórico")
                    .isEqualTo(Rol.CIUDADANO);
            assertThat(usuario.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Poner activo=false es lo que cierra las sesiones vivas")
        void laCuentaQuedaInactiva() {
            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            // No hace falta revocar nada: JwtAuthFilter recarga el usuario en cada
            // petición, así que el token que tuviera en la mano muere en la
            // siguiente. Es la misma maquinaria del bloqueo de RF-11.
            assertThat(usuario.getActivo()).isFalse();
        }

        @Test
        @DisplayName("La credencial que queda no puede validar ninguna contraseña")
        void laCredencialQuedaInutilizable() {
            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            String hashFinal = usuario.getPasswordHash();
            assertThat(hashFinal)
                    .as("sin formato BCrypt, matches() devuelve false sin comparar nada")
                    .doesNotStartWith("$2a$")
                    .doesNotStartWith("$2b$")
                    .doesNotStartWith("$2y$");
            assertThat(hashFinal).isNotEqualTo("$2a$12$hash");
        }

        @Test
        @DisplayName("El email anonimizado es determinista: el mismo email da el mismo resultado")
        void elEmailAnonimizadoEsDeterminista() {
            AnonimizacionService anonimizacion = new AnonimizacionService();

            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            // Es lo que permite a AuthService reconocerlo al registrar y no dejar
            // que otro se quede con el email de quien se fue.
            assertThat(usuario.getEmail()).isEqualTo(anonimizacion.emailAnonimizado(EMAIL));
            assertThat(usuario.getEmail())
                    .as("y el dominio no existe ni puede existir (RFC 2606)")
                    .endsWith("@cuenta-eliminada.invalid");
        }

        @Test
        @DisplayName("Dos cuentas distintas no colisionan al anonimizarse")
        void dosCuentasNoColisionan() {
            AnonimizacionService anonimizacion = new AnonimizacionService();

            assertThat(anonimizacion.emailAnonimizado("ana@test.es"))
                    .isNotEqualTo(anonimizacion.emailAnonimizado("luis@test.es"));
        }

        @Test
        @DisplayName("La caja del email no cambia el resultado: no dejaría libre una variante")
        void laCajaDelEmailNoImporta() {
            AnonimizacionService anonimizacion = new AnonimizacionService();

            assertThat(anonimizacion.emailAnonimizado("  Ana@Test.ES  "))
                    .isEqualTo(anonimizacion.emailAnonimizado("ana@test.es"));
        }
    }

    @Nested
    @DisplayName("Baja de una protectora")
    class BajaDeProtectora {

        /**
         * QUÉ se retira y por qué los ADOPTADO no se tocan lo comprueba
         * AnimalAdopcionServiceTest, que es donde vive el código desde el bloque
         * de RF-06. Aquí solo importa que la baja lo pida: era un método privado
         * de UsuarioService, y por serlo la retirada solo ocurría en esta vía
         * mientras el administrador dejaba los animales en el escaparate.
         */
        @Test
        @DisplayName("La baja manda retirar el catálogo de su protectora")
        void laBajaRetiraElCatalogo() {
            prepararProtectora();

            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            verify(animalAdopcionService)
                    .retirarTodosLosDisponibles(eq(ID_PROTECTORA), anyString());
        }

        @Test
        @DisplayName("Un ciudadano no dispara la retirada de animales")
        void elCiudadanoNoTocaElCatalogo() {
            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            verify(animalAdopcionService, never())
                    .retirarTodosLosDisponibles(any(), anyString());
        }

        @Test
        @DisplayName("Una cuenta PROTECTORA sin organización se da de baja igual")
        void sinOrganizacionSeDaDeBajaIgual() {
            usuario.setRol(Rol.PROTECTORA);
            when(protectoraRepository.findByUsuarioId(ID_USUARIO)).thenReturn(Optional.empty());

            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            // No debería existir, pero si existe no es motivo para atrapar a
            // alguien dentro de la plataforma.
            assertThat(usuario.getActivo()).isFalse();
        }

        @Test
        @DisplayName("Si era una persona física, su nombre y su dirección también desaparecen")
        void laPersonaFisicaSeAnonimizaEntera() {
            // El caso real: alguien que rescata por su cuenta, sin estructura
            // legal, que puso su propio nombre como organización y su domicilio
            // como dirección. Conservarlos sería dejar expuesta a una persona que
            // acaba de pedir que la borren.
            Protectora particular = protectora();
            particular.setEsPersonaFisica(true);
            particular.setNombreOrganizacion("María Gómez Ruiz");
            particular.setDireccion("C/ del Olmo 14, 3ºB");
            prepararProtectora(particular);

            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            assertThat(particular.getNombreOrganizacion()).isEqualTo("Protectora eliminada");
            assertThat(particular.getDireccion()).isNull();
        }

        @Test
        @DisplayName("Si era una entidad, el nombre y la dirección se conservan")
        void laEntidadConservaSusDatos() {
            // Son datos de una organización, no de una persona, y siguen
            // explicando de dónde salió cada animal que publicó.
            Protectora asociacion = protectora();
            asociacion.setEsPersonaFisica(false);
            prepararProtectora(asociacion);

            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            assertThat(asociacion.getNombreOrganizacion()).isEqualTo("Huellas");
            assertThat(asociacion.getDireccion()).isEqualTo("C/ Mayor 1");
        }

        @Test
        @DisplayName("La zona geográfica no se toca en ningún caso: no identifica a nadie")
        void laZonaSeConservaSiempre() {
            Protectora particular = protectora();
            particular.setEsPersonaFisica(true);
            prepararProtectora(particular);

            usuarioService.eliminarCuenta(ID_USUARIO, CLAVE);

            // Es una comarca o una ciudad, y es lo que permite que el histórico
            // siga diciendo dónde operaba quien publicó cada animal.
            assertThat(particular.getZonaGeografica()).isEqualTo("Madrid");
        }

        private void prepararProtectora() {
            prepararProtectora(protectora());
        }

        private void prepararProtectora(Protectora protectora) {
            usuario.setRol(Rol.PROTECTORA);
            when(protectoraRepository.findByUsuarioId(ID_USUARIO)).thenReturn(Optional.of(protectora));
        }
    }

    // ---------------------------------------------------------------------

    private static Usuario ciudadano() {
        Usuario usuario = new Usuario();
        usuario.setId(ID_USUARIO);
        usuario.setNombre("Ana");
        usuario.setEmail(EMAIL);
        usuario.setPasswordHash("$2a$12$hash");
        usuario.setTelefono("600000000");
        usuario.setAreaGeografica("Madrid");
        usuario.setFotoUrl("https://example.test/ana.jpg");
        usuario.setRol(Rol.CIUDADANO);
        usuario.setIdioma("es");
        usuario.setActivo(true);
        usuario.setCreatedAt(java.time.Instant.now());
        return usuario;
    }

    private static Protectora protectora() {
        Protectora protectora = new Protectora();
        protectora.setId(ID_PROTECTORA);
        protectora.setNombreOrganizacion("Huellas");
        protectora.setDireccion("C/ Mayor 1");
        protectora.setZonaGeografica("Madrid");
        protectora.setEstado(EstadoProtectora.ACTIVA);
        protectora.setEsPersonaFisica(false);
        return protectora;
    }

    private static AnimalAdopcion animal(EstadoAnimal estado) {
        AnimalAdopcion animal = new AnimalAdopcion();
        animal.setId(33L);
        animal.setNombre("Luna");
        animal.setEstado(estado);
        return animal;
    }
}
