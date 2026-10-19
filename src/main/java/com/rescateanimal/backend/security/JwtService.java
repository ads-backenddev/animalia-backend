package com.rescateanimal.backend.security;

import com.rescateanimal.backend.exception.TokenInvalidoException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Generación y validación de los JWT de la plataforma (RF-13, RF-17).
 *
 * Se emiten dos tipos de token, distinguidos por el claim "tipo": el de acceso,
 * que autentica las peticiones a la API, y el de restablecimiento de contraseña,
 * que viaja en el enlace enviado por email. Separarlos es deliberado: un token de
 * restablecimiento robado no debe servir para llamar a la API, ni al revés.
 */
@Service
public class JwtService {

    public static final String TIPO_ACCESO = "acceso";
    public static final String TIPO_RESET = "reset";

    private static final String CLAIM_TIPO = "tipo";

    private final String secreto;
    private final long expiracionAccesoMs;
    private final long expiracionResetMs;

    private SecretKey clave;

    public JwtService(
            @Value("${jwt.secret}") String secreto,
            @Value("${jwt.expiration}") long expiracionAccesoMs,
            @Value("${jwt.reset-expiration}") long expiracionResetMs) {
        this.secreto = secreto;
        this.expiracionAccesoMs = expiracionAccesoMs;
        this.expiracionResetMs = expiracionResetMs;
    }

    /**
     * HS256 exige una clave de al menos 256 bits. Se comprueba al arrancar para
     * que un secreto demasiado corto falle de inmediato y no en la primera
     * petición de login.
     */
    @PostConstruct
    void inicializarClave() {
        byte[] bytes = secreto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret debe tener al menos 32 caracteres para firmar con HS256");
        }
        this.clave = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * Token de acceso emitido junto a su caducidad.
     *
     * Se devuelven juntos para que quien lo genera no tenga que volver a parsear
     * y verificar la firma del token que acaba de crear solo para leer cuándo
     * expira: es trabajo criptográfico redundante justo en el login, que RNF-01
     * obliga a mantener por debajo de 2 s.
     */
    public record TokenAcceso(String token, Instant expiraEn) {
    }

    /**
     * Token de acceso para las peticiones autenticadas a la API.
     *
     * LLEVA LO MÍNIMO, Y ESO ES DELIBERADO. Antes incluía además el id y el rol
     * del usuario, que no leía nadie: JwtAuthFilter recarga la fila en cada
     * petición y construye las autoridades desde ella, precisamente para que un
     * bloqueo o un cambio de rol surtan efecto en el acto en vez de esperar a que
     * caduque el token. Los dos claims solo servían para publicar quién eres y
     * qué puedes hacer a cualquiera que leyera el token —un JWT va firmado, no
     * cifrado, así que su contenido se lee en claro— y el token aparece en sitios
     * incómodos, como la query del handshake WebSocket, que acaba en los registros
     * del servidor (RNF-10).
     *
     * Queda el subject con el email, que sí hace falta para localizar la cuenta,
     * y el jti, con el que se revoca en el logout.
     */
    public TokenAcceso generarTokenAcceso(UsuarioPrincipal usuario) {
        Instant ahora = Instant.now();
        Instant expiracion = ahora.plusMillis(expiracionAccesoMs);
        String token = Jwts.builder()
                .subject(usuario.getEmail())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TIPO, TIPO_ACCESO)
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(expiracion))
                .signWith(clave)
                .compact();
        return new TokenAcceso(token, expiracion);
    }

    /** Token de un solo uso para el enlace de recuperación de contraseña (RF-17). */
    public String generarTokenReset(String email) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .subject(email)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TIPO, TIPO_RESET)
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plusMillis(expiracionResetMs)))
                .signWith(clave)
                .compact();
    }

    /**
     * Verifica firma y caducidad y devuelve los claims.
     *
     * @throws TokenInvalidoException si el token está manipulado, caducado o no
     *                                es del tipo esperado.
     */
    public Claims validarYExtraer(String token, String tipoEsperado) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(clave)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenInvalidoException("El token no es válido o ha caducado");
        }
        if (!tipoEsperado.equals(claims.get(CLAIM_TIPO, String.class))) {
            throw new TokenInvalidoException("El token no es válido para esta operación");
        }
        return claims;
    }

    public String getEmail(Claims claims) {
        return claims.getSubject();
    }

    /** Identificador único del token (jti), con el que se revoca en el logout. */
    public String getJti(Claims claims) {
        return claims.getId();
    }

    public Instant getExpiracion(Claims claims) {
        return claims.getExpiration().toInstant();
    }

    /**
     * Momento de emisión (claim "iat"), con el que se comprueba si el token es
     * anterior al último cambio de contraseña del usuario.
     */
    public Instant getEmitidoEn(Claims claims) {
        return claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
    }
}
