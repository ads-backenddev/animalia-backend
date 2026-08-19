package com.rescateanimal.backend.security;

import com.rescateanimal.backend.model.Usuario;
import com.rescateanimal.backend.model.enums.Rol;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

/**
 * Adaptador entre la entidad Usuario y el modelo de usuario de Spring Security.
 *
 * Lleva el id además del email para que los services puedan identificar al
 * usuario autenticado sin volver a consultar la base de datos en cada petición.
 */
@Getter
@RequiredArgsConstructor
public class UsuarioPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Rol rol;
    private final boolean activo;

    /** Corte de validez de los tokens; nulo si nunca se cambió la contraseña. */
    private final LocalDateTime passwordChangedAt;

    public static UsuarioPrincipal de(Usuario usuario) {
        return new UsuarioPrincipal(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getPasswordHash(),
                usuario.getRol(),
                Boolean.TRUE.equals(usuario.getActivo()),
                usuario.getPasswordChangedAt()
        );
    }

    /**
     * Extrae el usuario del Principal de una sesión WebSocket.
     *
     * En el mundo STOMP no hay SecurityContext ni @AuthenticationPrincipal: lo
     * que llega a los @MessageMapping y a los interceptores es el Principal que
     * UsuarioHandshakeHandler fijó al aceptar el handshake, que es la
     * Authentication con este objeto dentro.
     *
     * @return el usuario autenticado, o null si el Principal no es uno de los
     *         nuestros —conexión sin autenticar—, para que quien llame decida
     *         cómo rechazarla
     */
    public static UsuarioPrincipal desde(Principal principal) {
        if (principal instanceof Authentication autenticacion
                && autenticacion.getPrincipal() instanceof UsuarioPrincipal usuario) {
            return usuario;
        }
        return null;
    }

    /**
     * Indica si un token emitido en ese instante sigue siendo válido, es decir,
     * si no es anterior al último cambio de contraseña.
     *
     * La comparación se hace con precisión de segundo porque el claim "iat" de un
     * JWT solo tiene esa granularidad: un token emitido en el mismo segundo del
     * cambio se considera válido, para no invalidar por redondeo el token que la
     * propia operación acaba de generar.
     */
    public boolean tokenEmitidoSigueVigente(Instant emitidoEn) {
        if (passwordChangedAt == null || emitidoEn == null) {
            return true;
        }
        Instant corte = passwordChangedAt.atZone(ZoneId.systemDefault()).toInstant();
        return !emitidoEn.isBefore(corte.minusSeconds(1));
    }

    /**
     * El prefijo ROLE_ es el que espera @PreAuthorize("hasRole('ADMIN')"),
     * que lo antepone automáticamente al nombre del rol.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + rol.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** Refleja el bloqueo de la cuenta por parte del administrador (RF-11). */
    @Override
    public boolean isEnabled() {
        return activo;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
