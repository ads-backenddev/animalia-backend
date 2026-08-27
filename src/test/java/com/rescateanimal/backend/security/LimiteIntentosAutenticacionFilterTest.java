package com.rescateanimal.backend.security;

import com.rescateanimal.backend.service.ControlIntentosService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * El freno a la fuerza bruta contra /api/auth (docs/api-rest.md §2).
 *
 * SE PRUEBA CON UN ControlIntentosService DE VERDAD, no simulado: lo que hay que
 * demostrar es el comportamiento observable —cuándo llega el 429 y cuándo deja de
 * llegar—, y con un contador simulado la prueba solo diría que el filtro llama a
 * quien tiene que llamar, que es justo la parte que no falla. Se envuelve en un
 * spy únicamente para poder contar invocaciones donde el efecto no se ve desde
 * fuera.
 *
 * LA PRUEBA QUE MÁS IMPORTA NO ES EL 429, sino que el cuerpo siga llegando al
 * controller: el filtro tiene que leer el email del JSON, y el cuerpo de una
 * petición es un flujo de un solo uso. Si CuerpoRepetibleRequest dejara de
 * funcionar, todos los logins de la aplicación empezarían a fallar con un 400 sin
 * relación aparente con este filtro.
 */
class LimiteIntentosAutenticacionFilterTest {

    private static final String LOGIN = "/api/auth/login";
    private static final String IP = "10.0.0.7";
    private static final String EMAIL = "ana@test.es";

    /** Los mismos valores que application.properties fija por defecto. */
    private static final int MAXIMO_POR_CUENTA = 5;
    private static final int MAXIMO_POR_IP = 30;
    private static final Duration VENTANA = Duration.ofMinutes(15);

    private ControlIntentosService control;
    private ObjectMapper objectMapper;
    private LimiteIntentosAutenticacionFilter filtro;

    @BeforeEach
    void prepararFiltro() {
        control = spy(new ControlIntentosService());

        // Jackson 3, que es el que Spring Boot 4 autoconfigura y el que recibe el
        // filtro en producción. Con el ObjectMapper de Jackson 2 esto ni siquiera
        // llegaría a ejecutarse: no hay bean de ese tipo y la aplicación no
        // arranca. Jackson 3 además serializa java.time de fábrica, así que el
        // Instant del ErrorResponse no necesita módulos aparte.
        objectMapper = new JsonMapper();

        filtro = new LimiteIntentosAutenticacionFilter(
                control, objectMapper, MAXIMO_POR_CUENTA, MAXIMO_POR_IP, (int) VENTANA.toMinutes());
    }

    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("El límite por cuenta se activa")
    class PorCuenta {

        @Test
        @DisplayName("Los cinco primeros fallos pasan; el sexto se corta con 429 antes del controller")
        void elSextoIntentoSeCorta() throws Exception {
            for (int i = 1; i <= MAXIMO_POR_CUENTA; i++) {
                Resultado permitido = fallo(EMAIL);
                assertThat(permitido.estado())
                        .as("el intento %d debe llegar al controller", i)
                        .isEqualTo(HttpStatus.UNAUTHORIZED.value());
                assertThat(permitido.pasoAlController()).isTrue();
            }

            Resultado cortado = fallo(EMAIL);

            assertThat(cortado.estado()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(cortado.pasoAlController())
                    .as("el 429 tiene que ahorrarse el BCrypt, no solo cambiar la respuesta")
                    .isFalse();
        }

        @Test
        @DisplayName("El 429 lleva Retry-After y el mismo cuerpo de error que el resto de la API")
        void elRechazoRespetaElContrato() throws Exception {
            Resultado cortado = agotarCuenta(EMAIL);

            assertThat(cortado.respuesta().getContentType())
                    .startsWith(MediaType.APPLICATION_JSON_VALUE);

            long espera = Long.parseLong(cortado.respuesta().getHeader(HttpHeaders.RETRY_AFTER));
            assertThat(espera)
                    .as("Retry-After debe decir cuánto falta de verdad, dentro de la ventana")
                    .isBetween(1L, VENTANA.getSeconds());

            JsonNode cuerpo = objectMapper.readTree(cortado.respuesta().getContentAsString());
            assertThat(cuerpo.get("status").asInt()).isEqualTo(429);
            assertThat(cuerpo.get("codigo").asString())
                    .isEqualTo(LimiteIntentosAutenticacionFilter.CODIGO);
            assertThat(cuerpo.get("ruta").asString()).isEqualTo(LOGIN);
            assertThat(cuerpo.get("mensaje").asString()).contains("cuenta");
            assertThat(cuerpo.has("timestamp")).isTrue();
        }

        @Test
        @DisplayName("El mensaje del 429 no delata si la cuenta existe")
        void elMensajeNoEsUnOraculo() throws Exception {
            Resultado real = agotarCuenta(EMAIL);
            Resultado inventada = agotarCuenta("no-existe@test.es");

            assertThat(textoDe(inventada, "mensaje"))
                    .as("el mismo texto para una cuenta real y una inventada")
                    .isEqualTo(textoDe(real, "mensaje"));
        }

        @Test
        @DisplayName("Alternar mayúsculas en el email no da un contador nuevo")
        void lasMayusculasNoEsquivanElLimite() throws Exception {
            // Sin normalizar, "Ana@Test.es" y "ana@test.es" serían cuentas distintas
            // para el contador y el límite no serviría absolutamente de nada: basta
            // con cambiar una letra de caja en cada intento.
            fallo("ana@test.es");
            fallo("Ana@Test.es");
            fallo("ANA@TEST.ES");
            fallo("  ana@test.es  ");
            fallo("aNa@tEsT.eS");

            assertThat(fallo("ana@test.es").estado())
                    .as("los seis intentos son contra la misma cuenta")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("Cada cuenta lleva su propio cupo")
        void bloquearUnaCuentaNoBloqueaOtra() throws Exception {
            agotarCuenta(EMAIL);

            assertThat(fallo("otro@test.es").estado())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
    }

    @Nested
    @DisplayName("El límite por IP se activa")
    class PorIp {

        @Test
        @DisplayName("Probar una contraseña común contra muchas cuentas se corta por IP")
        void elRelleoDeCredencialesSeCorta() throws Exception {
            // Ninguna cuenta llega a cinco intentos, así que el límite por cuenta no
            // ve nada. Este es exactamente el hueco que tapa el límite por IP.
            for (int i = 0; i < MAXIMO_POR_IP; i++) {
                assertThat(fallo("victima" + i + "@test.es").estado())
                        .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            }

            Resultado cortado = fallo("victima-final@test.es");

            assertThat(cortado.estado()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(cortado.pasoAlController()).isFalse();
            assertThat(textoDe(cortado, "mensaje")).contains("conexión");
        }

        @Test
        @DisplayName("Otra IP no arrastra el bloqueo de la primera")
        void elBloqueoNoAlcanzaAOtraIp() throws Exception {
            for (int i = 0; i <= MAXIMO_POR_IP; i++) {
                intentar(LOGIN, IP, cuerpo("v" + i + "@test.es"), 401);
            }

            Resultado otra = intentar(LOGIN, "10.0.0.8", cuerpo("alguien@test.es"), 401);

            assertThat(otra.estado()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("La IP se comprueba antes que la cuenta, sin llegar a mirar el cuerpo")
        void laIpSeCompruebaPrimero() throws Exception {
            for (int i = 0; i < MAXIMO_POR_IP; i++) {
                intentar(LOGIN, IP, cuerpo("v" + i + "@test.es"), 401);
            }

            // Una vez agotado el cupo por IP, la petición muere antes de abrir el
            // cuerpo: el email de esta cuenta, que no había fallado nunca, no llega a
            // contarse. Si el orden fuera el contrario, un ataque por IP gastaría de
            // paso el cupo de cuentas ajenas.
            Resultado cortado = fallo(EMAIL);

            assertThat(cortado.estado()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(textoDe(cortado, "mensaje")).contains("conexión");
            verify(control, never()).registrarYComprobar(eq("cuenta:" + EMAIL), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("El límite se reinicia")
    class SeReinicia {

        @Test
        @DisplayName("Entrar bien borra los fallos previos de esa cuenta")
        void elExitoDevuelveElCupo() throws Exception {
            for (int i = 0; i < MAXIMO_POR_CUENTA - 1; i++) {
                fallo(EMAIL);
            }

            assertThat(exito(EMAIL).estado()).isEqualTo(HttpStatus.OK.value());

            // Quien ya ha demostrado ser el dueño no arrastra los fallos anteriores:
            // el cupo tiene que estar entero otra vez.
            for (int i = 1; i <= MAXIMO_POR_CUENTA; i++) {
                assertThat(fallo(EMAIL).estado())
                        .as("tras entrar bien, el fallo %d debe admitirse", i)
                        .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            }
            assertThat(fallo(EMAIL).estado()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("Entrar bien NO devuelve cupo por IP")
        void elExitoNoDevuelveElCupoDeLaIp() throws Exception {
            // Si acertar reiniciara también el contador por IP, bastaría con tener una
            // cuenta propia válida para renovar el cupo entre tanteo y tanteo, y el
            // límite por IP dejaría de frenar el relleno de credenciales.
            for (int i = 0; i < MAXIMO_POR_IP; i++) {
                assertThat(exito("propia" + i + "@test.es").estado())
                        .isEqualTo(HttpStatus.OK.value());
            }

            assertThat(fallo("victima@test.es").estado())
                    .as("treinta aciertos no compran un cupo nuevo")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("Un 4xx del controller no cuenta como éxito")
        void soloEl2xxReinicia() throws Exception {
            for (int i = 0; i < MAXIMO_POR_CUENTA - 1; i++) {
                fallo(EMAIL);
            }
            // Una cuenta bloqueada responde 403: la contraseña era correcta, pero no
            // es un login válido y no debe devolver el cupo.
            intentar(LOGIN, IP, cuerpo(EMAIL), HttpStatus.FORBIDDEN.value());

            assertThat(fallo(EMAIL).estado()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    @Nested
    @DisplayName("El cuerpo sigue llegando al controller")
    class CuerpoIntacto {

        @Test
        @DisplayName("Leer el email en el filtro no deja sin cuerpo a quien viene detrás")
        void elControllerRecibeElCuerpoEntero() throws Exception {
            String enviado = cuerpo(EMAIL);

            Resultado resultado = intentar(LOGIN, IP, enviado, 200);

            assertThat(resultado.cadena().cuerpoVisto)
                    .as("si esto falla, todos los logins de la aplicación dan 400")
                    .isEqualTo(enviado);
        }

        @Test
        @DisplayName("Un cuerpo que no es JSON pasa igualmente, y solo cuenta por IP")
        void elCuerpoIlegiblePasa() throws Exception {
            Resultado resultado = intentar(LOGIN, IP, "esto no es json", 400);

            assertThat(resultado.pasoAlController()).isTrue();
            assertThat(resultado.cadena().cuerpoVisto).isEqualTo("esto no es json");
            verify(control, never()).registrarYComprobar(startsWith("cuenta:"), anyInt(), any());
        }

        @Test
        @DisplayName("Un JSON sin campo email pasa, y solo cuenta por IP")
        void elJsonSinEmailPasa() throws Exception {
            Resultado resultado = intentar(LOGIN, IP, "{\"password\":\"x\"}", 400);

            assertThat(resultado.pasoAlController()).isTrue();
            verify(control, never()).registrarYComprobar(startsWith("cuenta:"), anyInt(), any());
        }

        @Test
        @DisplayName("Un cuerpo enorme no se carga en memoria: pasa intacto y solo cuenta por IP")
        void elCuerpoEnormeNoSeInspecciona() throws Exception {
            // El filtro no puede convertirse en la forma de agotar la memoria del
            // servidor a base de peticiones gigantes.
            byte[] enorme = new byte[17 * 1024];
            java.util.Arrays.fill(enorme, (byte) 'a');

            MockHttpServletRequest peticion = peticionPost(LOGIN, IP);
            peticion.setContent(enorme);
            MockHttpServletResponse respuesta = new MockHttpServletResponse();
            Cadena cadena = new Cadena(200);
            filtro.doFilter(peticion, respuesta, cadena);

            assertThat(cadena.llamadas).isOne();
            assertThat(cadena.cuerpoVisto).hasSize(enorme.length);
            verify(control, never()).registrarYComprobar(startsWith("cuenta:"), anyInt(), any());
            verify(control).registrarYComprobar(eq("ip:" + IP), anyInt(), any());
        }

        @Test
        @DisplayName("Un cuerpo vacío pasa sin romper")
        void elCuerpoVacioPasa() throws Exception {
            Resultado resultado = intentar(LOGIN, IP, null, 400);

            assertThat(resultado.pasoAlController()).isTrue();
            verify(control, never()).registrarYComprobar(startsWith("cuenta:"), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("Qué se vigila y qué no")
    class Alcance {

        @ParameterizedTest(name = "{0} {1} -> vigilada = {2}")
        @CsvSource({
                "POST, /api/auth/login,                true",
                "POST, /api/auth/registro,             true",
                "POST, /api/auth/registro/protectora,  true",
                "POST, /api/auth/recuperar-password,   true",
                "POST, /api/auth/restablecer-password, false",
                "POST, /api/auth/logout,               false",
                "POST, /api/alertas,                   false",
                "GET,  /api/auth/login,                false"
        })
        @DisplayName("Solo los POST de login, registro y recuperación")
        void soloSeVigilanLasRutasDeAdivinarContrasena(String metodo, String ruta, boolean vigilada) {
            MockHttpServletRequest peticion = new MockHttpServletRequest(metodo, ruta);

            assertThat(filtro.shouldNotFilter(peticion)).isEqualTo(!vigilada);
        }

        @Test
        @DisplayName("restablecer-password no se limita nunca, por muchos intentos que reciba")
        void elRestablecimientoNoSeLimita() throws Exception {
            // Su credencial es un JWT firmado, no una contraseña que se pueda
            // adivinar a mano; y bloquearlo dejaría sin salida justo a quien intenta
            // recuperar una cuenta comprometida. Ver docs/api-rest.md §17.
            for (int i = 0; i < 50; i++) {
                Resultado resultado =
                        intentar("/api/auth/restablecer-password", IP, cuerpo(EMAIL), 400);
                assertThat(resultado.estado())
                        .as("intento %d", i)
                        .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            }
        }
    }

    @Nested
    @DisplayName("Una petición, un intento")
    class UnSoloConteo {

        @Test
        @DisplayName("Spring Boot inscribe el filtro dos veces, pero solo cuenta una")
        void laDobleInscripcionNoCuentaDosVeces() throws Exception {
            // Un bean Filter queda registrado en la cadena de Spring Security Y
            // además en la del servlet. Si el filtro se ejecutara las dos veces, el
            // cupo real sería la mitad del anunciado y un usuario legítimo se
            // quedaría fuera a los dos o tres intentos. Lo que lo impide es
            // OncePerRequestFilter, y esto lo comprueba en vez de darlo por hecho.
            //
            // LA SEGUNDA PASADA VA ANIDADA DENTRO DE LA PRIMERA, que es como ocurre
            // de verdad: la cadena del servlet envuelve a la de Spring Security. Y
            // el anidamiento no es un detalle de estilo: OncePerRequestFilter marca
            // la petición al entrar y la desmarca en un finally, así que la marca
            // solo existe mientras la primera pasada sigue en la pila. Dos llamadas
            // seguidas contarían dos veces sin que eso delate ningún fallo.
            MockHttpServletRequest peticion = peticionPost(LOGIN, IP);
            peticion.setContent(cuerpo(EMAIL).getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse respuesta = new MockHttpServletResponse();

            Cadena interior = new Cadena(401);
            FilterChain exterior = (req, res) -> filtro.doFilter(req, res, interior);

            filtro.doFilter(peticion, respuesta, exterior);

            assertThat(interior.llamadas)
                    .as("la petición tiene que llegar al controller pese a la doble pasada")
                    .isOne();
            assertThat(interior.cuerpoVisto)
                    .as("y con su cuerpo intacto, envuelto dos veces o no")
                    .isEqualTo(cuerpo(EMAIL));
            verify(control, times(1))
                    .registrarYComprobar(eq("cuenta:" + EMAIL), anyInt(), any());
            verify(control, times(1))
                    .registrarYComprobar(eq("ip:" + IP), anyInt(), any());
        }
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /** Cadena de filtros que anota si la han llamado y qué cuerpo le llegó. */
    private static final class Cadena implements FilterChain {

        private final int estadoDelController;
        private int llamadas;
        private String cuerpoVisto;

        private Cadena(int estadoDelController) {
            this.estadoDelController = estadoDelController;
        }

        @Override
        public void doFilter(ServletRequest peticion, ServletResponse respuesta) throws IOException {
            llamadas++;
            cuerpoVisto = new String(peticion.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ((HttpServletResponse) respuesta).setStatus(estadoDelController);
        }
    }

    private record Resultado(MockHttpServletResponse respuesta, Cadena cadena) {

        int estado() {
            return respuesta.getStatus();
        }

        boolean pasoAlController() {
            return cadena.llamadas > 0;
        }
    }

    private Resultado fallo(String email) throws Exception {
        return intentar(LOGIN, IP, cuerpo(email), HttpStatus.UNAUTHORIZED.value());
    }

    private Resultado exito(String email) throws Exception {
        return intentar(LOGIN, IP, cuerpo(email), HttpStatus.OK.value());
    }

    /** Gasta el cupo de una cuenta y devuelve el intento que ya se rechaza. */
    private Resultado agotarCuenta(String email) throws Exception {
        for (int i = 0; i < MAXIMO_POR_CUENTA; i++) {
            fallo(email);
        }
        return fallo(email);
    }

    private Resultado intentar(String ruta, String ip, String cuerpo, int estadoDelController)
            throws Exception {

        MockHttpServletRequest peticion = peticionPost(ruta, ip);
        if (cuerpo != null) {
            peticion.setContent(cuerpo.getBytes(StandardCharsets.UTF_8));
        }
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        Cadena cadena = new Cadena(estadoDelController);

        filtro.doFilter(peticion, respuesta, cadena);

        return new Resultado(respuesta, cadena);
    }

    private static MockHttpServletRequest peticionPost(String ruta, String ip) {
        MockHttpServletRequest peticion = new MockHttpServletRequest("POST", ruta);
        peticion.setRemoteAddr(ip);
        peticion.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return peticion;
    }

    private static String cuerpo(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"passw0rd123\"}";
    }

    private String textoDe(Resultado resultado, String campo) throws IOException {
        return objectMapper.readTree(resultado.respuesta().getContentAsString()).get(campo).asString();
    }
}
