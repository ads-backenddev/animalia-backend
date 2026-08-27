package com.rescateanimal.backend.security;

import com.rescateanimal.backend.dto.response.ErrorResponse;
import com.rescateanimal.backend.service.ControlIntentosService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Limita los intentos contra los endpoints de autenticación (login, registro y
 * recuperación de contraseña).
 *
 * EL PROBLEMA QUE CIERRA. Sin esto, POST /api/auth/login admite intentos
 * ilimitados desde una misma dirección contra una misma cuenta, y con
 * contraseñas de mínimo ocho caracteres sin requisito de composición, un
 * diccionario de las contraseñas más comunes es perfectamente viable. El único
 * freno era el coste de BCrypt, que además juega en contra: cada intento le
 * cuesta al servidor unos 100 ms de CPU y al atacante prácticamente nada, así
 * que unos cientos de peticiones concurrentes degradan la API entera y RNF-01
 * obliga a responder el login en menos de 2 s.
 *
 * POR ESO ES UN FILTRO Y NO UNA COMPROBACIÓN EN AuthService: aquí la petición se
 * corta antes de llegar al controller, sin consultar la base de datos y sin
 * ejecutar BCrypt. Frenar la fuerza bruta desde dentro del service dejaría en pie
 * la mitad del problema, que es el gasto.
 *
 * DOS LÍMITES, QUE MIDEN COSAS DISTINTAS:
 *
 * - POR CUENTA (5 en 15 min) es la defensa real contra adivinar una contraseña
 *   concreta. Se reinicia al autenticarse con éxito: los fallos anteriores eran
 *   de alguien que ya ha demostrado ser el dueño.
 * - POR IP (30 en 15 min) es el respaldo contra lo que el anterior no ve:
 *   probar una contraseña común contra muchas cuentas distintas, donde ninguna
 *   llega a cinco intentos. No se reinicia al acertar, porque hacerlo permitiría
 *   renovar el cupo entre tanteos con solo tener una cuenta propia válida.
 *
 * EL LÍMITE POR CUENTA ES ARMA DE DOBLE FILO Y SE ASUME: un tercero puede
 * dispararlo contra la cuenta de otro y dejarlo sin entrar hasta quince minutos.
 * Es el precio conocido de cualquier bloqueo por cuenta. Se mitiga con que la
 * ventana no se renueva mientras dure el bloqueo (ver ControlIntentosService), de
 * modo que el castigo tiene techo y no depende de cuánto insista el atacante.
 *
 * LA IP SE TOMA DE getRemoteAddr() Y NO DE X-Forwarded-For, a propósito: esa
 * cabecera la pone el cliente y se falsifica sola, así que fiarse de ella
 * convierte el límite por IP en un adorno —basta con mandar una distinta en cada
 * intento—. Si algún día esto se despliega detrás de un proxy inverso, habrá que
 * leerla, pero solo tras configurar la lista de proxies de confianza que permite
 * distinguir el salto real del inventado.
 */
@Slf4j
@Component
public class LimiteIntentosAutenticacionFilter extends OncePerRequestFilter {

    static final String CODIGO = "DEMASIADOS_INTENTOS";
    static final String CODIGO_CUERPO = "CUERPO_DEMASIADO_GRANDE";

    static final String RUTA_LOGIN = "/api/auth/login";
    private static final String RUTA_REGISTRO = "/api/auth/registro";
    private static final String RUTA_RECUPERAR = "/api/auth/recuperar-password";

    /**
     * Rutas vigiladas. restablecer-password queda fuera a propósito: su credencial
     * es un JWT firmado, no una contraseña que se pueda adivinar a mano, y
     * bloquearlo dejaría sin salida justo a quien está intentando recuperar una
     * cuenta comprometida.
     */
    private static final String[] RUTAS = {RUTA_LOGIN, RUTA_REGISTRO, RUTA_RECUPERAR};

    /**
     * Tope de cuerpo que se acepta cargar en memoria para leer el email. Los
     * cuerpos de autenticación son de unos cientos de bytes; por encima de esto la
     * petición se rechaza con 413, para no convertir el filtro en una forma de
     * agotar la memoria del servidor a base de peticiones enormes.
     *
     * EL TOPE LO IMPONE LA LECTURA, NO LA CABECERA Content-Length, y la distinción
     * es justo el agujero que tenía antes: getContentLengthLong() devuelve -1
     * cuando la longitud no se conoce —cualquier petición con
     * Transfer-Encoding: chunked, que es HTTP corriente—, y -1 pasa cualquier
     * comparación de «menor que». Con eso, un anónimo mandaba un cuerpo
     * arbitrariamente grande a un endpoint público y el filtro se lo cargaba
     * entero en memoria. Fiarse de una cifra que declara el cliente para decidir
     * cuánto leer no tenía arreglo con un ajuste; se lee acotado y punto.
     */
    private static final int MAXIMO_CUERPO_BYTES = 16 * 1024;

    private final ControlIntentosService controlIntentos;

    /**
     * El ObjectMapper de Jackson 3 (tools.jackson), que es el que Spring Boot 4
     * autoconfigura y con el que se serializa el resto de respuestas de la API.
     *
     * NO ES el com.fasterxml.jackson de toda la vida, y conviene dejarlo escrito
     * porque el import correcto no es el que sugiere el IDE: Jackson 2 sigue en el
     * classpath de arrastre, así que pedirlo compila igual, pero no existe ningún
     * bean de ese tipo y la aplicación entera se niega a arrancar con un
     * "No qualifying bean of type com.fasterxml.jackson.databind.ObjectMapper".
     * Además Jackson 2 llegaría aquí sin el módulo de java.time, de modo que
     * escribir el Instant del ErrorResponse convertiría este 429 en un 500.
     */
    private final ObjectMapper objectMapper;

    private final int maximoPorCuenta;
    private final int maximoPorIp;
    private final Duration ventana;

    /**
     * Los tres límites son configurables, y no por gusto de parametrizar: la
     * propia suite de pruebas de integración sale entera de 127.0.0.1, así que
     * entre todas agotaba el cupo por IP y la siguiente petición de registro
     * recibía un 429 que no tenía nada que ver con lo que estaba probando. Un
     * despliegue detrás de una NAT compartida —un instituto, una oficina— tiene
     * exactamente el mismo problema y necesita la misma salida.
     */
    public LimiteIntentosAutenticacionFilter(
            ControlIntentosService controlIntentos,
            ObjectMapper objectMapper,
            @Value("${app.auth.max-intentos-cuenta}") int maximoPorCuenta,
            @Value("${app.auth.max-intentos-ip}") int maximoPorIp,
            @Value("${app.auth.ventana-intentos-minutos}") int ventanaMinutos) {

        this.controlIntentos = controlIntentos;
        this.objectMapper = objectMapper;
        this.maximoPorCuenta = maximoPorCuenta;
        this.maximoPorIp = maximoPorIp;
        this.ventana = Duration.ofMinutes(ventanaMinutos);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || rutaVigilada(request) == null;
    }

    /** La ruta vigilada que atiende esta petición, o null si no es ninguna. */
    private static String rutaVigilada(HttpServletRequest request) {
        String ruta = request.getRequestURI();
        for (String vigilada : RUTAS) {
            if (ruta.startsWith(vigilada)) {
                return vigilada;
            }
        }
        return null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String accion = rutaVigilada(request);
        String clavePorIp = "ip:" + request.getRemoteAddr();
        if (controlIntentos.registrarYComprobar(clavePorIp, maximoPorIp, ventana)) {
            log.warn("Demasiados intentos de autenticación desde una misma dirección en {}",
                    request.getRequestURI());
            rechazar(response, clavePorIp,
                    "Demasiados intentos desde esta conexión. Inténtalo de nuevo más tarde",
                    request.getRequestURI());
            return;
        }

        CuerpoRepetibleRequest peticion =
                new CuerpoRepetibleRequest(request, MAXIMO_CUERPO_BYTES);

        if (peticion.isDesbordado()) {
            log.warn("Cuerpo por encima de {} bytes en {}", MAXIMO_CUERPO_BYTES, request.getRequestURI());
            responder(response, HttpStatus.PAYLOAD_TOO_LARGE, CODIGO_CUERPO,
                    "El cuerpo de la petición es demasiado grande", request.getRequestURI(), null);
            return;
        }

        String email = extraerEmail(peticion.getCuerpo());

        // LA CLAVE LLEVA LA ACCIÓN, y no es cosmético: con una sola clave por
        // email, los contadores de las tres rutas eran el mismo, y como
        // recuperar-password responde 204 SIEMPRE —no revela si la cuenta existe,
        // ver AuthController—, bastaba con intercalar una llamada suya para que el
        // reinicio por éxito borrase los intentos fallidos de login. Cuatro
        // contraseñas, una solicitud de recuperación, otras cuatro contraseñas, y
        // el límite por cuenta no se disparaba jamás.
        String clavePorCuenta = email == null ? null : "cuenta:" + accion + ":" + email;

        if (clavePorCuenta != null
                && controlIntentos.registrarYComprobar(clavePorCuenta, maximoPorCuenta, ventana)) {
            // No se registra el email: es dato personal (RNF-10).
            log.warn("Demasiados intentos de autenticación contra una misma cuenta en {}",
                    request.getRequestURI());
            rechazar(response, clavePorCuenta,
                    "Demasiados intentos con esta cuenta. Inténtalo de nuevo en unos minutos",
                    request.getRequestURI());
            return;
        }

        filterChain.doFilter(peticion, response);

        // SOLO UN LOGIN CORRECTO DEVUELVE EL CUPO. Es lo único que demuestra que
        // quien está al otro lado es el dueño de la cuenta; un 201 de registro o
        // un 204 de recuperación no demuestran nada, porque se obtienen sin
        // conocer la contraseña. Y solo el contador por cuenta: reiniciar el de la
        // IP permitiría renovar el cupo entre tanteos con tener una cuenta propia.
        if (clavePorCuenta != null && RUTA_LOGIN.equals(accion) && esExito(response.getStatus())) {
            controlIntentos.reiniciar(clavePorCuenta);
        }
    }

    /**
     * Saca el email del cuerpo JSON sin consumirlo para quien viene detrás.
     *
     * SE NORMALIZA IGUAL QUE EN AuthService —recortado y en minúsculas— y no es un
     * detalle: sin ello, alternar mayúsculas en el email daría un contador nuevo
     * en cada intento y el límite por cuenta no serviría absolutamente de nada.
     *
     * Un cuerpo ilegible o sin campo email no es un error aquí: significa que la
     * petición va a fallar de todas formas en la validación del controller. Se
     * deja pasar sin contar por cuenta, y sigue contando por IP.
     */
    private String extraerEmail(byte[] cuerpo) {
        if (cuerpo == null || cuerpo.length == 0) {
            return null;
        }
        try {
            JsonNode raiz = objectMapper.readTree(new String(cuerpo, StandardCharsets.UTF_8));
            JsonNode email = raiz.get("email");
            if (email == null || !email.isString()) {
                return null;
            }
            String normalizado = email.asString().trim().toLowerCase();
            return normalizado.isEmpty() ? null : normalizado;

        } catch (JacksonException e) {
            log.debug("Cuerpo de autenticación no interpretable como JSON: {}", e.getMessage());
            return null;
        }
    }

    private static boolean esExito(int estado) {
        return estado >= 200 && estado < 300;
    }

    /** El 429 del límite de intentos, con lo que falta para volver a probar. */
    private void rechazar(HttpServletResponse response, String clave, String mensaje, String ruta)
            throws IOException {

        responder(response, HttpStatus.TOO_MANY_REQUESTS, CODIGO, mensaje, ruta,
                controlIntentos.segundosParaReintentar(clave, ventana));
    }

    /**
     * Escribe el mismo cuerpo de error que GlobalExceptionHandler daría a
     * cualquier otro fallo.
     *
     * Se escribe a mano porque un filtro se ejecuta antes del DispatcherServlet:
     * una excepción lanzada aquí no llega a ningún @ExceptionHandler, así que
     * dejarla subir daría una página de error de Tomcat en lugar de JSON.
     */
    private void responder(HttpServletResponse response, HttpStatus estado, String codigo,
                           String mensaje, String ruta, Long esperaSegundos) throws IOException {

        response.setStatus(estado.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (esperaSegundos != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(esperaSegundos));
        }

        ErrorResponse cuerpo = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(estado.value())
                .error(estado.getReasonPhrase())
                .codigo(codigo)
                .mensaje(mensaje)
                .ruta(ruta)
                .build();

        objectMapper.writeValue(response.getOutputStream(), cuerpo);
    }
}
