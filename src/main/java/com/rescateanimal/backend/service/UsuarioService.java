package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.ActualizarPerfilRequest;
import com.rescateanimal.backend.dto.response.UsuarioResponse;
import com.rescateanimal.backend.exception.CredencialesInvalidasException;
import com.rescateanimal.backend.exception.ParametrosInvalidosException;
import com.rescateanimal.backend.exception.ResourceNotFoundException;
import com.rescateanimal.backend.model.AnimalAdopcion;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoAnimal;
import com.rescateanimal.backend.model.enums.Idioma;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.AnimalAdopcionRepository;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Perfil propio: consulta, personalización, idioma y baja de la cuenta
 * (RF-02, RF-18, RF-19).
 *
 * TODO ES SOBRE UNO MISMO. Ningún método recibe el id de otro usuario: los cuatro
 * parten del id del token. Por eso no hay ni una comprobación de propiedad que
 * hacer —no se puede ni pedir el perfil ajeno— y por eso el controller no lleva
 * anotación de rol: no hay rol que exigir, solo estar autenticado.
 *
 * =====================================================================
 * LA BAJA NO BORRA: ANONIMIZA. Es la decisión de fondo del bloque.
 * =====================================================================
 *
 * Un DELETE en cascada sobre USUARIO habría arrastrado las nueve tablas que le
 * apuntan: alertas, avistamientos, matches, chats, mensajes, valoraciones,
 * reportes, notificaciones y contadores. Y con ellas se habría llevado por
 * delante la mitad de conversaciones ajenas, porque un chat tiene DOS
 * participantes: al borrar a uno, el otro pierde el hilo entero de algo que
 * quizá siga en marcha —una mascota que aún no ha aparecido, una adopción a
 * medias— y ni siquiera puede releer lo ya hablado. Quien se da de baja tiene
 * derecho a que sus datos personales desaparezcan, no a borrar la memoria de las
 * conversaciones de los demás.
 *
 * Así que las filas se quedan y lo que desaparece es la persona: nombre, email,
 * teléfono, foto y credencial. Lo que queda apuntando a ese usuario_id es una
 * cuenta sin dueño, con la que no se puede entrar ni a la que se puede escribir.
 *
 * NO HACE FALTA CERRAR LAS SESIONES A MANO. Poner activo=false basta, y no por
 * casualidad: JwtAuthFilter recarga la fila del usuario en CADA petición
 * precisamente para que un bloqueo surta efecto en el acto (RF-11), y
 * ChatSuscripcionInterceptor hace lo mismo en cada SUBSCRIBE desde el bloque de
 * refuerzo. La baja se apoya en esa maquinaria en lugar de traer la suya: el
 * token que el usuario tenga en la mano deja de valer en la siguiente petición,
 * sin revocar nada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final ProtectoraRepository protectoraRepository;
    private final AnimalAdopcionService animalAdopcionService;
    private final PasswordEncoder passwordEncoder;
    private final AnonimizacionService anonimizacion;
    private final FotoService fotoService;

    // ---------------------------------------------------------------------
    // Perfil (RF-02, RF-18)
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public UsuarioResponse perfil(Long usuarioId) {
        return aResponse(exigirUsuario(usuarioId));
    }

    /**
     * Personaliza el perfil (RF-02).
     *
     * Fija los cuatro campos, también los que vengan vacíos: es un PUT, y su
     * promesa es que el recurso queda como dice el cuerpo. Mandar el teléfono a
     * null es como se borra un teléfono.
     *
     * El nombre se recorta porque acaba a la vista de otros —en cada chat, en
     * cada alerta—, y " Ana " no es un nombre distinto de "Ana". Los otros tres
     * se recortan por lo mismo, y una cadena que solo tenía espacios se guarda
     * como null en vez de como cadena vacía: vacío y ausente son lo mismo aquí, y
     * tener dos formas de decirlo obliga a comprobar las dos en todas partes.
     */
    @Transactional
    public UsuarioResponse actualizarPerfil(Long usuarioId, ActualizarPerfilRequest peticion) {
        Usuario usuario = exigirUsuario(usuarioId);

        // Solo se comprueba una foto nueva: reenviar la que ya tiene el perfil
        // —el cliente manda el perfil entero— no debe fallar aunque sea de antes
        // de /api/fotos. Va antes de tocar ningún campo, para que un 400 no deje
        // la entidad a medio modificar.
        String fotoUrl = limpiar(peticion.getFotoUrl());
        if (fotoUrl != null && !fotoUrl.equals(usuario.getFotoUrl())) {
            fotoService.exigirPropia(fotoUrl, usuarioId);
        }

        String fotoAnterior = usuario.getFotoUrl();

        usuario.setNombre(peticion.getNombre().trim());
        usuario.setTelefono(limpiar(peticion.getTelefono()));
        usuario.setAreaGeografica(limpiar(peticion.getAreaGeografica()));
        usuario.setFotoUrl(fotoUrl);

        // La foto que se cambia o se quita deja de publicarse: si no, cada foto de
        // perfil antigua seguiría servida para siempre y la baja (RF-19) solo
        // borraría la última.
        if (fotoAnterior != null && !fotoAnterior.equals(fotoUrl)) {
            fotoService.retirar(fotoAnterior, usuarioId);
        }

        log.debug("Perfil actualizado del usuario {}", usuarioId);
        return aResponse(usuario);
    }

    /**
     * Cambia el idioma de la aplicación (RF-18).
     *
     * El código se valida contra Idioma y no contra una lista escrita aquí: es la
     * única lista, y el mensaje de error enumera lo admitido para que el cliente
     * no tenga que adivinarlo. Se guarda el código canónico en minúscula, de modo
     * que "ES" y "es" acaban siendo la misma fila.
     */
    @Transactional
    public UsuarioResponse cambiarIdioma(Long usuarioId, String codigo) {
        Idioma idioma = Idioma.desdeCodigo(codigo)
                .orElseThrow(() -> new ParametrosInvalidosException(
                        "Idioma no admitido. Los disponibles son: " + Idioma.codigosAdmitidos()));

        Usuario usuario = exigirUsuario(usuarioId);
        usuario.setIdioma(idioma.getCodigo());

        log.debug("El usuario {} ha cambiado el idioma a {}", usuarioId, idioma.getCodigo());
        return aResponse(usuario);
    }

    // ---------------------------------------------------------------------
    // Baja de la cuenta (RF-19)
    // ---------------------------------------------------------------------

    /**
     * Da de baja la cuenta propia de forma irreversible (RF-19).
     *
     * EXIGE LA CONTRASEÑA aunque la petición ya venga autenticada. Es la única
     * operación irreversible de la API, y un token demuestra que alguien inició
     * sesión, no que quien la lanza ahora mismo sea el dueño. Se responde 401 si
     * no coincide, el mismo código que un login fallido: es exactamente el mismo
     * hecho, una credencial que no vale.
     *
     * QUÉ SE ANONIMIZA, y por qué cada cosa:
     * - nombre: pasa a uno genérico, el mismo para todos. Uno con el id dentro
     *   —"Usuario 482"— seguiría distinguiendo a una persona de otra a lo largo
     *   de la aplicación, que es justo lo que se quiere deshacer.
     * - email: se sustituye por un hash suyo en un dominio inexistente, así que
     *   desaparece y a la vez no queda libre para que otro lo registre. Ver
     *   AnonimizacionService.
     * - passwordHash: un valor sin formato BCrypt, con el que ningún login puede
     *   acertar. La columna es NOT NULL, de ahí que se ponga algo.
     * - teléfono y foto: a null. Son datos personales sin ninguna función en el
     *   histórico. La foto de perfil, además, deja de existir: su fila se borra
     *   con esta transacción y su archivo justo después (FotoService.retirar).
     *   Las fotos de sus alertas y avistamientos NO: son fotos de animales y
     *   forman parte del histórico que la otra parte necesita ver.
     * - activo: false, que es lo que cierra las sesiones vivas.
     *
     * NO SE TOCA NADA MÁS: ni el rol, ni la fecha de alta, ni una sola fila de las
     * tablas que apuntan aquí, con una única excepción: la fila de FOTO de su foto
     * de perfil, que es la propia foto que se borra. El histórico se conserva
     * entero, fotos de sus publicaciones incluidas.
     *
     * Es irrepetible en la práctica: una cuenta ya anonimizada no puede volver a
     * pasar por aquí, porque su credencial ya no valida ninguna contraseña y su
     * activo=false impide que el token llegue siquiera al controller.
     */
    @Transactional
    public void eliminarCuenta(Long usuarioId, String password) {
        Usuario usuario = exigirUsuario(usuarioId);

        if (!passwordEncoder.matches(password, usuario.getPasswordHash())) {
            log.warn("Intento de baja del usuario {} con contraseña incorrecta", usuarioId);
            throw new CredencialesInvalidasException("La contraseña no es correcta");
        }

        if (usuario.getRol() == Rol.PROTECTORA) {
            darDeBajaLaProtectora(usuarioId);
        }

        usuario.setNombre(anonimizacion.nombreAnonimo());
        usuario.setEmail(anonimizacion.emailAnonimizado(usuario.getEmail()));
        usuario.setPasswordHash(anonimizacion.hashInutilizable());
        usuario.setTelefono(null);
        String foto = usuario.getFotoUrl();
        usuario.setFotoUrl(null);
        if (foto != null) {
            fotoService.retirar(foto, usuarioId);
        }
        usuario.setActivo(false);

        // No se registra ni el email ni el nombre: la baja es justo el momento en
        // que esos datos dejan de poder guardarse en ninguna parte (RNF-10).
        log.info("Cuenta {} dada de baja y anonimizada", usuarioId);
    }

    /**
     * Deja la protectora sin catálogo y, si era una persona, sin datos.
     *
     * RETIRAR LO PUBLICADO NO SE HACE AQUÍ desde el bloque de RF-06: vive en
     * AnimalAdopcionService.retirarTodosLosDisponibles, que es quien gobierna los
     * estados del animal. Era un método privado de esta clase, y por serlo solo lo
     * aplicaba la baja voluntaria: rechazar o bloquear a una protectora dejaba sus
     * animales ofreciéndose en el escaparate con nadie detrás para responder. El
     * razonamiento completo —por qué pasan a RETIRADO y por qué los ADOPTADO no se
     * tocan— está allí, en el único sitio donde ya está escrito.
     *
     * LA FILA DE PROTECTORA SOLO SE ANONIMIZA SI ERA UNA PERSONA. Ver
     * anonimizarSiEsPersonaFisica.
     */
    private void darDeBajaLaProtectora(Long usuarioId) {
        Protectora protectora = protectoraRepository.findByUsuarioId(usuarioId).orElse(null);
        if (protectora == null) {
            // Una cuenta con rol PROTECTORA sin fila de organización no debería
            // existir, pero si existe no es motivo para impedirle darse de baja.
            log.warn("La cuenta {} tiene rol PROTECTORA y no tiene organización asociada", usuarioId);
            return;
        }

        animalAdopcionService.retirarTodosLosDisponibles(
                protectora.getId(), "su cuenta se ha dado de baja");
        anonimizarSiEsPersonaFisica(protectora);
    }

    /**
     * Borra el nombre y la dirección cuando detrás de la protectora había una
     * persona particular, no una entidad.
     *
     * POR QUÉ DEPENDE DE UN CAMPO Y NO ES SIEMPRE. Para una asociación
     * registrada, el nombre y la dirección son datos de la entidad: siguen
     * apareciendo en las fichas de los animales que publicó, y borrarlos dejaría
     * cada adopción histórica sin decir de dónde salió el animal. Pero en España
     * es muy común que una protectora sea alguien que rescata por su cuenta, sin
     * estructura legal, y entonces esas dos columnas son su nombre y su domicilio
     * particular: conservarlas sería dejar expuesta a una persona que acaba de
     * pedir que la borren. Los dos casos existen y no se distinguen mirando el
     * dato, así que se pregunta al registrarse (RF-19, §19).
     *
     * LA ZONA GEOGRÁFICA NO SE TOCA. Es una comarca o una ciudad —"Madrid",
     * "Bajo Deba"—, no una dirección: no identifica a nadie y es lo que permite
     * que el histórico siga diciendo dónde operaba quien publicó cada animal.
     *
     * ES TAN IRREVERSIBLE COMO EL RESTO: no se guarda copia en ninguna parte, ni
     * hay tabla de auditoría de la que rescatarlo.
     */
    private void anonimizarSiEsPersonaFisica(Protectora protectora) {
        if (!Boolean.TRUE.equals(protectora.getEsPersonaFisica())) {
            return;
        }

        protectora.setNombreOrganizacion(anonimizacion.organizacionAnonima());
        protectora.setDireccion(null);

        // Sin el nombre ni la dirección en el log, por lo mismo que en la cuenta:
        // la baja es justo el momento en que dejan de poder guardarse (RNF-10).
        log.info("Protectora {} anonimizada: era una persona física", protectora.getId());
    }

    // ---------------------------------------------------------------------

    private Usuario exigirUsuario(Long usuarioId) {
        return usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El usuario " + usuarioId + " no existe"));
    }

    /** Recorta y convierte en null lo que solo tenía espacios. */
    private static String limpiar(String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.trim();
        return recortado.isEmpty() ? null : recortado;
    }

    private static UsuarioResponse aResponse(Usuario usuario) {
        return UsuarioResponse.builder()
                .id(usuario.getId())
                .nombre(usuario.getNombre())
                .email(usuario.getEmail())
                .telefono(usuario.getTelefono())
                .areaGeografica(usuario.getAreaGeografica())
                .fotoUrl(usuario.getFotoUrl())
                .rol(usuario.getRol())
                .idioma(usuario.getIdioma())
                .activo(usuario.getActivo())
                .createdAt(usuario.getCreatedAt())
                .build();
    }
}
