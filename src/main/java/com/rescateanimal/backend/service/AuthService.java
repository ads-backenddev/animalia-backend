package com.rescateanimal.backend.service;

import com.rescateanimal.backend.dto.request.LoginRequest;
import com.rescateanimal.backend.dto.request.RecuperarPasswordRequest;
import com.rescateanimal.backend.dto.request.RegistroCiudadanoRequest;
import com.rescateanimal.backend.dto.request.RegistroProtectoraRequest;
import com.rescateanimal.backend.dto.request.RestablecerPasswordRequest;
import com.rescateanimal.backend.dto.response.LoginResponse;
import com.rescateanimal.backend.dto.response.ProtectoraResponse;
import com.rescateanimal.backend.dto.response.UsuarioResponse;
import com.rescateanimal.backend.exception.CredencialesInvalidasException;
import com.rescateanimal.backend.exception.CuentaNoAutorizadaException;
import com.rescateanimal.backend.exception.EmailYaRegistradoException;
import com.rescateanimal.backend.exception.TokenInvalidoException;
import com.rescateanimal.backend.model.Protectora;
import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.EstadoProtectora;
import com.rescateanimal.backend.model.enums.Idioma;
import com.rescateanimal.backend.model.enums.Rol;
import com.rescateanimal.backend.repository.ProtectoraRepository;
import com.rescateanimal.backend.repository.UsuarioRepository;
import com.rescateanimal.backend.security.JwtService;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Lógica de registro, login, logout y recuperación de contraseña
 * (RF-01, RF-13, RF-16, RF-17).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** El idioma con el que nace una cuenta que no elige ninguno. La lista vive en Idioma. */
    private static final String IDIOMA_POR_DEFECTO = Idioma.POR_DEFECTO.getCodigo();

    /**
     * Hash BCrypt de una contraseña arbitraria, usado solo para gastar el mismo
     * tiempo de cómputo cuando el email del login no existe. Nunca se compara
     * con una credencial real.
     */
    private static final String HASH_DESCARTE =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UsuarioRepository usuarioRepository;
    private final ProtectoraRepository protectoraRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenRevocadoService tokenRevocadoService;
    private final EmailService emailService;
    private final AnonimizacionService anonimizacionService;

    // ---------------------------------------------------------------------
    // Registro (RF-01)
    // ---------------------------------------------------------------------

    /** Alta de un ciudadano: cuenta activa desde el primer momento. */
    @Transactional
    public UsuarioResponse registrarCiudadano(RegistroCiudadanoRequest peticion) {
        verificarEmailLibre(peticion.getEmail());

        Usuario usuario = new Usuario();
        usuario.setNombre(peticion.getNombre());
        usuario.setEmail(normalizarEmail(peticion.getEmail()));
        usuario.setPasswordHash(passwordEncoder.encode(peticion.getPassword()));
        usuario.setTelefono(peticion.getTelefono());
        usuario.setAreaGeografica(peticion.getAreaGeografica());
        usuario.setRol(Rol.CIUDADANO);
        usuario.setIdioma(idiomaDe(peticion.getIdioma()));
        usuario.setActivo(true);

        return aUsuarioResponse(usuarioRepository.save(usuario));
    }

    /**
     * Alta de una protectora: crea la cuenta y su organización en estado
     * PENDIENTE. La cuenta queda activa, pero el login se rechaza mientras la
     * protectora no esté verificada por el administrador (RF-11).
     */
    @Transactional
    public ProtectoraResponse registrarProtectora(RegistroProtectoraRequest peticion) {
        verificarEmailLibre(peticion.getEmail());

        Usuario usuario = new Usuario();
        usuario.setNombre(peticion.getNombre());
        usuario.setEmail(normalizarEmail(peticion.getEmail()));
        usuario.setPasswordHash(passwordEncoder.encode(peticion.getPassword()));
        usuario.setTelefono(peticion.getTelefono());
        usuario.setAreaGeografica(peticion.getZonaGeografica());
        usuario.setRol(Rol.PROTECTORA);
        usuario.setIdioma(IDIOMA_POR_DEFECTO);
        usuario.setActivo(true);
        Usuario guardado = usuarioRepository.save(usuario);

        Protectora protectora = new Protectora();
        protectora.setUsuario(guardado);
        protectora.setNombreOrganizacion(peticion.getNombreOrganizacion());
        protectora.setDireccion(peticion.getDireccion());
        protectora.setZonaGeografica(peticion.getZonaGeografica());
        protectora.setEstado(EstadoProtectora.PENDIENTE);

        return aProtectoraResponse(protectoraRepository.save(protectora));
    }

    // ---------------------------------------------------------------------
    // Login y logout (RF-13, RF-16)
    // ---------------------------------------------------------------------

    /**
     * Valida las credenciales y emite el token de acceso.
     *
     * La comprobación de la contraseña se hace aquí con el PasswordEncoder en
     * lugar de delegar en el AuthenticationManager, porque hace falta distinguir
     * tres desenlaces con códigos HTTP distintos: credenciales incorrectas (401),
     * cuenta bloqueada (403) y protectora sin verificar (403).
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest peticion) {
        Optional<Usuario> encontrado = usuarioRepository.findByEmail(normalizarEmail(peticion.getEmail()));

        if (encontrado.isEmpty()) {
            // Se compara igualmente contra un hash de descarte. Sin esto, un email
            // inexistente respondería sin pagar los ~100 ms de BCrypt y el tiempo
            // de respuesta delataría qué cuentas existen.
            passwordEncoder.matches(peticion.getPassword(), HASH_DESCARTE);
            throw new CredencialesInvalidasException("Email o contraseña incorrectos");
        }

        Usuario usuario = encontrado.get();
        if (!passwordEncoder.matches(peticion.getPassword(), usuario.getPasswordHash())) {
            throw new CredencialesInvalidasException("Email o contraseña incorrectos");
        }

        verificarCuentaOperativa(usuario);

        JwtService.TokenAcceso token = jwtService.generarTokenAcceso(UsuarioPrincipal.de(usuario));

        return LoginResponse.builder()
                .token(token.token())
                .tipo("Bearer")
                .expiraEn(token.expiraEn())
                .usuario(aUsuarioResponse(usuario))
                .build();
    }

    /**
     * Invalida el token en curso (RF-16). Es idempotente: cerrar sesión con un
     * token ya caducado o ya revocado no es un error para el cliente.
     */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtService.validarYExtraer(token, JwtService.TIPO_ACCESO);
            tokenRevocadoService.revocar(jwtService.getJti(claims), jwtService.getExpiracion(claims));
        } catch (TokenInvalidoException e) {
            log.debug("Logout con un token que ya no era válido: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Recuperación de contraseña (RF-17)
    // ---------------------------------------------------------------------

    /**
     * Envía el enlace de restablecimiento al email registrado.
     *
     * No revela si el email existe: cuando no hay cuenta, el método termina en
     * silencio y el controller responde 204 igualmente. De lo contrario el
     * endpoint sería un oráculo para enumerar las cuentas de la plataforma.
     */
    @Transactional(readOnly = true)
    public void solicitarRecuperacion(RecuperarPasswordRequest peticion) {
        Optional<Usuario> usuario = usuarioRepository.findByEmail(normalizarEmail(peticion.getEmail()));
        if (usuario.isEmpty()) {
            log.debug("Solicitud de recuperación para un email sin cuenta");
            return;
        }
        String token = jwtService.generarTokenReset(usuario.get().getEmail());
        emailService.enviarEnlaceRecuperacion(usuario.get().getEmail(), usuario.get().getNombre(), token);
    }

    /**
     * Fija la nueva contraseña a partir del token del enlace y lo revoca, de modo
     * que el enlace del correo sirve una sola vez aunque aún no haya caducado.
     */
    @Transactional
    public void restablecerPassword(RestablecerPasswordRequest peticion) {
        Claims claims = jwtService.validarYExtraer(peticion.getToken(), JwtService.TIPO_RESET);

        String jti = jwtService.getJti(claims);
        if (tokenRevocadoService.estaRevocado(jti)) {
            throw new TokenInvalidoException("Este enlace de recuperación ya se ha utilizado");
        }

        Usuario usuario = usuarioRepository.findByEmail(jwtService.getEmail(claims))
                .orElseThrow(() -> new TokenInvalidoException("El token no es válido o ha caducado"));

        // Una cuenta bloqueada por el administrador no puede operar, tampoco para
        // cambiarse la contraseña (RF-11).
        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw new CuentaNoAutorizadaException("CUENTA_BLOQUEADA",
                    "Tu cuenta ha sido bloqueada por un administrador");
        }

        // Comprobación persistente del "un solo uso": un enlace emitido antes del
        // último cambio de contraseña ya no vale, aunque la lista de tokens
        // revocados se haya perdido al reiniciar la aplicación.
        if (!UsuarioPrincipal.de(usuario).tokenEmitidoSigueVigente(jwtService.getEmitidoEn(claims))) {
            throw new TokenInvalidoException("Este enlace de recuperación ya no es válido");
        }

        usuario.setPasswordHash(passwordEncoder.encode(peticion.getNuevaPassword()));
        // Invalida de golpe todos los tokens de acceso ya emitidos: si la cuenta
        // estaba comprometida, el atacante pierde el acceso al instante (RF-17).
        usuario.setPasswordChangedAt(Instant.now());
        usuarioRepository.save(usuario);

        tokenRevocadoService.revocar(jti, jwtService.getExpiracion(claims));
    }

    // ---------------------------------------------------------------------
    // Apoyo
    // ---------------------------------------------------------------------

    /**
     * Rechaza el registro si el email ya está tomado, y también si lo estuvo.
     *
     * LA SEGUNDA COMPROBACIÓN ES LA INTERESANTE. Al darse de baja, el email se
     * sustituye por un hash suyo (RF-19, ver AnonimizacionService), así que la
     * dirección original ya no aparece en ninguna fila y la primera comprobación
     * la daría por libre. Dejar que otro la registrara sería un problema real: en
     * cada conversación que la persona de baja dejó abierta, el interlocutor
     * seguiría viendo el mismo hilo, y quien escribiera desde esa dirección
     * pasaría por ser la misma persona con la que estuvo hablando. El histórico se
     * conserva justo para que esas conversaciones sigan teniendo sentido, y esto
     * lo volvería del revés.
     *
     * Se busca el email anonimizado en vez del original: es determinista, así que
     * calcularlo y preguntar si existe basta para reconocerlo sin haber guardado
     * la dirección en ninguna parte.
     *
     * El mensaje es el mismo en los dos casos, a propósito. Distinguir "ya
     * registrado" de "perteneció a una cuenta dada de baja" convertiría este
     * endpoint en una forma de averiguar quién se ha ido de la plataforma.
     */
    private void verificarEmailLibre(String email) {
        String normalizado = normalizarEmail(email);

        // NADIE SE REGISTRA EN EL DOMINIO RESERVADO DE LAS CUENTAS BORRADAS, y
        // esto no es celo de más: ese dominio es donde acaban los emails
        // anonimizados, y son deterministas. Sin esta comprobación, quien conociera
        // el email de otra persona podía calcular su hash, registrarse con esa
        // dirección y dejar una mina: el día que la víctima quisiera darse de baja,
        // el UPDATE de su email chocaría contra el índice único y la baja fallaría
        // con un 409 incomprensible. Es decir, se le podía impedir ejercer RF-19 de
        // forma indefinida, y sin que el mensaje de error dijera por qué.
        if (anonimizacionService.esDominioReservado(normalizado)) {
            log.warn("Intento de registro en el dominio reservado de cuentas eliminadas");
            throw new EmailYaRegistradoException("Ya existe una cuenta con ese email");
        }

        // El || corta: si el email ya está en uso no hace falta calcular el hash
        // ni volver a consultar. La segunda comprobación solo interesa cuando la
        // dirección parece libre, que es justo el caso en el que puede no estarlo.
        if (usuarioRepository.existsByEmail(normalizado)
                || usuarioRepository.existsByEmail(anonimizacionService.emailAnonimizado(normalizado))) {

            throw new EmailYaRegistradoException("Ya existe una cuenta con ese email");
        }
    }

    /**
     * El idioma con el que nace la cuenta, siempre uno de los admitidos.
     *
     * Un código no reconocido cae al por defecto en vez de rechazar el registro:
     * el idioma es opcional en el alta y equivocarse en él no es motivo para
     * impedir que alguien se dé de alta. Quien quiera cambiarlo tiene
     * PUT /api/usuarios/me/idioma, que sí responde 400 y dice cuáles valen, porque
     * ahí el idioma es lo único que se está pidiendo.
     *
     * Antes se guardaba tal cual lo mandara el cliente, con el único límite de
     * diez caracteres: "klingon" era un idioma perfectamente válido para la base
     * de datos, y nadie lo habría notado hasta ir a traducir la interfaz.
     */
    private static String idiomaDe(String codigo) {
        return Idioma.desdeCodigo(codigo).orElse(Idioma.POR_DEFECTO).getCodigo();
    }

    /** Los emails se guardan en minúsculas para que el login no dependa de cómo se teclee. */
    private String normalizarEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /** Traduce a 403 los dos motivos por los que una cuenta válida no puede entrar. */
    private void verificarCuentaOperativa(Usuario usuario) {
        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw new CuentaNoAutorizadaException("CUENTA_BLOQUEADA",
                    "Tu cuenta ha sido bloqueada por un administrador");
        }
        if (usuario.getRol() == Rol.PROTECTORA) {
            EstadoProtectora estado = protectoraRepository.findByUsuarioId(usuario.getId())
                    .map(Protectora::getEstado)
                    .orElse(EstadoProtectora.PENDIENTE);
            if (estado == EstadoProtectora.PENDIENTE) {
                throw new CuentaNoAutorizadaException("PROTECTORA_PENDIENTE",
                        "Tu protectora está pendiente de verificación por un administrador");
            }
            if (estado == EstadoProtectora.RECHAZADA) {
                throw new CuentaNoAutorizadaException("PROTECTORA_RECHAZADA",
                        "La solicitud de tu protectora ha sido rechazada");
            }
        }
    }

    private UsuarioResponse aUsuarioResponse(Usuario usuario) {
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

    /**
     * SIN valoracionMedia, y no es un campo que se haya olvidado: este DTO solo
     * se construye al registrar una protectora, y una que acaba de nacer no
     * tiene ninguna valoración por definición. Antes se copiaba
     * PROTECTORA.valoracion_media, que era nula siempre porque nadie la
     * mantenía; la columna se eliminó en la migración V9 y la media se calcula
     * donde de verdad se lee (ValoracionService). Aquí el valor correcto es la
     * ausencia, y el campo se omite del JSON.
     */
    private ProtectoraResponse aProtectoraResponse(Protectora protectora) {
        return ProtectoraResponse.builder()
                .id(protectora.getId())
                .nombreOrganizacion(protectora.getNombreOrganizacion())
                .direccion(protectora.getDireccion())
                .zonaGeografica(protectora.getZonaGeografica())
                .estado(protectora.getEstado())
                .createdAt(protectora.getCreatedAt())
                .usuario(aUsuarioResponse(protectora.getUsuario()))
                .build();
    }
}
