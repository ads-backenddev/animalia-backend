package com.rescateanimal.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuenta intentos por clave dentro de una ventana deslizante, para frenar la
 * fuerza bruta contra los endpoints de autenticación.
 *
 * VENTANA DESLIZANTE Y NO CONTADOR POR TRAMOS. Un contador que se pone a cero
 * cada cuarto de hora deja pasar el doble del límite a caballo entre dos tramos:
 * cinco intentos al final de uno y cinco al principio del siguiente son diez
 * seguidos. Aquí se guarda la marca de tiempo de cada intento y se descartan las
 * que quedan fuera de la ventana, así que el límite se cumple en cualquier
 * intervalo de esa duración, no solo en los que empiezan en punto.
 *
 * EL ALMACENAMIENTO ES EN MEMORIA, y a diferencia de TokenRevocadoService aquí
 * está bien que lo sea. Perder los contadores al reiniciar solo regala al
 * atacante los intentos que llevara acumulados, no le abre ninguna puerta; y el
 * dato es intrínsecamente efímero, con una vida útil de quince minutos. Una tabla
 * añadiría dos escrituras por cada intento de login, justo en el endpoint que
 * RNF-01 obliga a mantener por debajo de 2 s. Si algún día se despliegan varias
 * instancias, cada una llevará su cuenta y el límite efectivo se multiplicará por
 * el número de instancias: ahí sí habría que moverlo a Redis, sin cambiar esta
 * interfaz.
 *
 * UN INTENTO BLOQUEADO NO ALARGA EL CASTIGO: cuando la clave ya está en el
 * límite, la marca no se registra. Así el bloqueo dura como mucho una ventana
 * desde el último intento admitido, en vez de renovarse indefinidamente mientras
 * alguien siga aporreando. Importa porque el límite por cuenta lo puede disparar
 * un tercero contra la cuenta de otro: es preferible que el legítimo espere
 * quince minutos a que quede fuera todo el tiempo que el atacante quiera.
 */
@Slf4j
@Service
public class ControlIntentosService {

    /**
     * Tope de claves vigiladas a la vez, para que la propia defensa no sea un
     * modo de agotar la memoria del servidor. Ver comprobarTope.
     */
    static final int MAXIMO_CLAVES = 100_000;

    /** clave -> marcas de tiempo de sus intentos recientes, de más viejo a más nuevo. */
    private final Map<String, Deque<Instant>> intentos = new ConcurrentHashMap<>();

    /**
     * Registra un intento y responde si con él se ha superado el límite.
     *
     * Las dos cosas van juntas y no en dos métodos porque tienen que ser
     * atómicas: entre un "¿cuántos van?" y un "apunta uno más" caben todas las
     * peticiones concurrentes que el atacante quiera, y el límite se le escapa
     * por ahí. Se resuelve dentro de un compute del mapa concurrente, que
     * garantiza que la función se aplica una sola vez por clave a la vez.
     *
     * @return true si este intento NO debe atenderse por haberse agotado el cupo
     */
    public boolean registrarYComprobar(String clave, int maximo, Duration ventana) {
        if (clave == null) {
            return false;
        }
        comprobarTope();

        Instant ahora = Instant.now();
        Instant corte = ahora.minus(ventana);
        boolean[] superado = {false};

        intentos.compute(clave, (k, marcas) -> {
            Deque<Instant> recientes = marcas == null ? new ArrayDeque<>() : marcas;

            // Fuera lo que ya no cuenta. Están ordenadas por construcción, así que
            // basta con descartar por el principio hasta llegar a una vigente.
            while (!recientes.isEmpty() && recientes.peekFirst().isBefore(corte)) {
                recientes.pollFirst();
            }

            if (recientes.size() >= maximo) {
                superado[0] = true;
            } else {
                recientes.addLast(ahora);
            }

            // Devolver null borra la entrada: una clave sin intentos vigentes no
            // debe seguir ocupando sitio en el mapa.
            return recientes.isEmpty() ? null : recientes;
        });

        return superado[0];
    }

    /**
     * Olvida los intentos de una clave. Se llama al autenticarse con éxito: los
     * fallos previos eran de alguien que ya ha demostrado ser el dueño.
     */
    public void reiniciar(String clave) {
        if (clave != null) {
            intentos.remove(clave);
        }
    }

    /**
     * Segundos que faltan para que la clave vuelva a admitir intentos, es decir,
     * hasta que su marca más antigua salga de la ventana.
     *
     * Sirve para la cabecera Retry-After del 429: sin ella el cliente no sabe si
     * esperar un segundo o un cuarto de hora, y lo razonable —reintentar— es
     * justo lo que mantiene el bloqueo vivo.
     */
    public long segundosParaReintentar(String clave, Duration ventana) {
        Deque<Instant> recientes = intentos.get(clave);
        if (recientes == null) {
            return 0;
        }
        Instant masAntigua = recientes.peekFirst();
        if (masAntigua == null) {
            return 0;
        }
        long segundos = Duration.between(Instant.now(), masAntigua.plus(ventana)).getSeconds();
        return Math.max(0, segundos);
    }

    /**
     * Evita que el mapa crezca sin límite.
     *
     * Sin esto, bastaría con enviar peticiones desde muchas direcciones
     * distintas para llenar la memoria del servidor: la defensa contra la fuerza
     * bruta se convertiría en el vector de una denegación de servicio. Primero se
     * intenta hacer sitio tirando lo caducado, que es lo normal; solo si aun así
     * se sigue por encima del tope se vacía entero.
     *
     * Vaciar regala los contadores en curso, y es una concesión consciente:
     * llegar aquí exige más de cien mil claves distintas vivas a la vez, o sea un
     * ataque distribuido a esa escala, contra el que un límite por IP no iba a
     * poder de todas formas. Entre perder los contadores y quedarse sin memoria,
     * se pierde lo primero.
     */
    private void comprobarTope() {
        if (intentos.size() < MAXIMO_CLAVES) {
            return;
        }

        Instant ahora = Instant.now();
        intentos.values().removeIf(marcas -> {
            Instant ultima = marcas.peekLast();
            // Una hora cubre de sobra cualquier ventana en uso; no hace falta
            // conocerlas aquí para saber que esto ya no le sirve a nadie.
            return ultima == null || ultima.isBefore(ahora.minus(Duration.ofHours(1)));
        });

        if (intentos.size() >= MAXIMO_CLAVES) {
            log.warn("Control de intentos por encima de {} claves tras purgar: se vacía. "
                    + "Esto solo pasa bajo un ataque distribuido a gran escala", MAXIMO_CLAVES);
            intentos.clear();
        }
    }
}
