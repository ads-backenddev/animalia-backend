package com.rescateanimal.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La ventana deslizante que frena la fuerza bruta contra la autenticación.
 */
class ControlIntentosServiceTest {

    private static final int MAXIMO = 5;
    private static final Duration VENTANA = Duration.ofMinutes(15);
    private static final String CLAVE = "cuenta:ana@test.es";

    private ControlIntentosService control;

    @BeforeEach
    void prepararServicio() {
        control = new ControlIntentosService();
    }

    @Nested
    @DisplayName("El límite se activa")
    class SeActiva {

        @Test
        @DisplayName("Los primeros intentos pasan y el que supera el máximo se corta")
        void elLimiteSeAlcanzaEnElIntentoCorrecto() {
            for (int i = 1; i <= MAXIMO; i++) {
                assertThat(control.registrarYComprobar(CLAVE, MAXIMO, VENTANA))
                        .as("el intento %d de %d debe admitirse", i, MAXIMO)
                        .isFalse();
            }

            assertThat(control.registrarYComprobar(CLAVE, MAXIMO, VENTANA))
                    .as("el intento %d supera el limite", MAXIMO + 1)
                    .isTrue();
        }

        @Test
        @DisplayName("Una vez bloqueada, la clave sigue bloqueada")
        void sigueBloqueadaMientrasDureLaVentana() {
            for (int i = 0; i <= MAXIMO; i++) {
                control.registrarYComprobar(CLAVE, MAXIMO, VENTANA);
            }

            assertThat(control.registrarYComprobar(CLAVE, MAXIMO, VENTANA)).isTrue();
            assertThat(control.registrarYComprobar(CLAVE, MAXIMO, VENTANA)).isTrue();
        }

        @Test
        @DisplayName("Cada clave lleva su propia cuenta: bloquear una no bloquea a las demás")
        void lasClavesSonIndependientes() {
            for (int i = 0; i <= MAXIMO; i++) {
                control.registrarYComprobar(CLAVE, MAXIMO, VENTANA);
            }

            assertThat(control.registrarYComprobar(CLAVE, MAXIMO, VENTANA)).isTrue();
            assertThat(control.registrarYComprobar("cuenta:otro@test.es", MAXIMO, VENTANA)).isFalse();
            assertThat(control.registrarYComprobar("ip:10.0.0.1", MAXIMO, VENTANA)).isFalse();
        }

        @Test
        @DisplayName("Bajo carga concurrente no se cuela ni un intento de más")
        void elLimiteAguantaLaConcurrencia() throws Exception {
            // Sin atomicidad entre "cuantos van" y "apunta uno mas", varias
            // peticiones simultaneas leerian el mismo contador y pasarian todas.
            // Es el escenario real de un ataque, no una rareza de laboratorio.
            int hilos = 32;
            ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
            CountDownLatch salida = new CountDownLatch(1);
            AtomicInteger admitidos = new AtomicInteger();

            for (int i = 0; i < hilos; i++) {
                ejecutor.submit(() -> {
                    salida.await();
                    if (!control.registrarYComprobar(CLAVE, MAXIMO, VENTANA)) {
                        admitidos.incrementAndGet();
                    }
                    return null;
                });
            }
            salida.countDown();
            ejecutor.shutdown();
            assertThat(ejecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(admitidos.get())
                    .as("con %d hilos a la vez solo pueden admitirse %d intentos", hilos, MAXIMO)
                    .isEqualTo(MAXIMO);
        }
    }

    @Nested
    @DisplayName("El límite se reinicia")
    class SeReinicia {

        @Test
        @DisplayName("Reiniciar la clave devuelve el cupo entero")
        void reiniciarDevuelveElCupo() {
            for (int i = 0; i <= MAXIMO; i++) {
                control.registrarYComprobar(CLAVE, MAXIMO, VENTANA);
            }
            assertThat(control.registrarYComprobar(CLAVE, MAXIMO, VENTANA)).isTrue();

            control.reiniciar(CLAVE);

            for (int i = 1; i <= MAXIMO; i++) {
                assertThat(control.registrarYComprobar(CLAVE, MAXIMO, VENTANA))
                        .as("tras reiniciar, el intento %d debe admitirse", i)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("Al salir de la ventana, los intentos viejos dejan de contar")
        void laVentanaDeslizaYLibera() {
            // La ventana se pasa como parametro, asi que basta con usar una corta
            // para observar el deslizamiento sin esperar quince minutos.
            Duration ventana = Duration.ofMillis(300);
            Instant inicio = Instant.now();

            for (int i = 0; i <= MAXIMO; i++) {
                control.registrarYComprobar(CLAVE, MAXIMO, ventana);
            }
            assertThat(control.registrarYComprobar(CLAVE, MAXIMO, ventana)).isTrue();

            esperarHasta(inicio, 450);

            assertThat(control.registrarYComprobar(CLAVE, MAXIMO, ventana))
                    .as("pasada la ventana, la clave vuelve a admitir intentos")
                    .isFalse();
        }

        @Test
        @DisplayName("Un intento bloqueado no alarga el castigo")
        void elBloqueoNoSeRenuevaAlInsistir() {
            // Si aporrear extendiera la ventana, un tercero podria dejar a otro sin
            // entrar indefinidamente. El castigo tiene que tener techo.
            //
            // LOS TIEMPOS SE MIDEN DESDE UN INSTANTE FIJO, no encadenando esperas:
            // sumar dormidas acumula el retraso de cada una y la prueba acaba
            // cruzando la frontera que pretende vigilar. Con la ventana en 1 s hay
            // 300 ms de margen a cada lado, que aguantan un equipo cargado.
            Duration ventana = Duration.ofMillis(1000);
            Instant inicio = Instant.now();

            for (int i = 0; i <= MAXIMO; i++) {
                control.registrarYComprobar(CLAVE, MAXIMO, ventana);
            }

            // Insistir mientras dura el bloqueo, como haria un atacante.
            for (long momento : new long[]{200, 400, 600}) {
                esperarHasta(inicio, momento);
                assertThat(control.registrarYComprobar(CLAVE, MAXIMO, ventana))
                        .as("a los %d ms la clave sigue bloqueada", momento)
                        .isTrue();
            }

            // La ventana se cuenta desde los intentos REALES, que fueron todos en
            // t=0, asi que a los 1300 ms ya esta libre. Si los intentos bloqueados
            // se apuntaran, el ultimo estaria en t=600 y seguiria cerrada hasta
            // t=1600: por eso esta espera discrimina entre las dos conductas.
            esperarHasta(inicio, 1300);

            assertThat(control.registrarYComprobar(CLAVE, MAXIMO, ventana))
                    .as("insistir no debe renovar el bloqueo")
                    .isFalse();
        }

        @Test
        @DisplayName("Reiniciar una clave que no existe no rompe, y un nulo tampoco")
        void reiniciarToleraLoInexistente() {
            control.reiniciar("cuenta:nadie@test.es");
            control.reiniciar(null);

            assertThat(control.registrarYComprobar(null, MAXIMO, VENTANA))
                    .as("sin clave no hay nada que limitar")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Retry-After")
    class Espera {

        @Test
        @DisplayName("Bloqueada, dice cuánto falta; libre, dice cero")
        void informaDeLaEsperaRestante() {
            assertThat(control.segundosParaReintentar(CLAVE, VENTANA))
                    .as("una clave sin intentos no hace esperar")
                    .isZero();

            for (int i = 0; i <= MAXIMO; i++) {
                control.registrarYComprobar(CLAVE, MAXIMO, VENTANA);
            }

            assertThat(control.segundosParaReintentar(CLAVE, VENTANA))
                    .isBetween(1L, VENTANA.getSeconds());
        }
    }

    @Nested
    @DisplayName("Tope de memoria")
    class Memoria {

        @Test
        @DisplayName("Las claves sin intentos vigentes no se quedan ocupando sitio")
        void lasClavesVaciasSeOlvidan() {
            Duration ventana = Duration.ofMillis(200);
            List<String> claves = List.of("ip:1.1.1.1", "ip:2.2.2.2", "ip:3.3.3.3");
            Instant inicio = Instant.now();

            claves.forEach(c -> control.registrarYComprobar(c, MAXIMO, ventana));
            esperarHasta(inicio, 350);

            // Al volver a tocarlas, la ventana vacia debe borrar la entrada en vez
            // de dejar un hueco: se comprueba por su efecto observable, que es que
            // el cupo esta entero.
            claves.forEach(c -> assertThat(control.registrarYComprobar(c, MAXIMO, ventana))
                    .isFalse());
        }
    }

    // ---------------------------------------------------------------------

    /**
     * Espera hasta un momento concreto contado desde el inicio de la prueba.
     *
     * Encadenar Thread.sleep acumula el retraso de cada dormida —el planificador
     * despierta cuando puede, no cuando toca— y con ventanas de milisegundos eso
     * basta para cruzar la frontera que la prueba vigila. Fijando el origen, cada
     * espera corrige la desviación de las anteriores.
     */
    private static void esperarHasta(Instant inicio, long milisegundosDesdeElInicio) {
        long falta = milisegundosDesdeElInicio - Duration.between(inicio, Instant.now()).toMillis();
        if (falta > 0) {
            esperar(Duration.ofMillis(falta));
        }
    }

    private static void esperar(Duration duracion) {
        try {
            Thread.sleep(duracion.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
