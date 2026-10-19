package com.rescateanimal.backend.controller;

import com.rescateanimal.backend.dto.request.ActualizarPerfilRequest;
import com.rescateanimal.backend.dto.request.CambiarIdiomaRequest;
import com.rescateanimal.backend.dto.request.EliminarCuentaRequest;
import com.rescateanimal.backend.dto.response.UsuarioResponse;
import com.rescateanimal.backend.security.UsuarioPrincipal;
import com.rescateanimal.backend.service.UsuarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfil propio: consulta, personalización, idioma y baja (RF-02, RF-18, RF-19).
 *
 * NINGUNA RUTA LLEVA {id}, y no es una simplificación: los cuatro endpoints son
 * sobre uno mismo y el usuario sale del token, nunca de la ruta ni del cuerpo. Así
 * no existe el caso de "tocar el perfil de otro" que haya que rechazar — no se
 * puede ni formular—, y con ello desaparece toda una familia de fallos de
 * autorización. Es el mismo criterio que ProtectoraPanelController.
 *
 * SIN ANOTACIÓN DE ROL, a diferencia del panel de protectora o del de
 * administrador: aquí no hay ningún rol que exigir. Un ciudadano, una protectora y
 * un administrador tienen todos perfil propio y todos pueden darse de baja.
 * SecurityConfig ya exige token para cualquier ruta que no sea /api/auth, y eso es
 * exactamente el permiso que hace falta.
 *
 * NO HAY ENDPOINT PARA VER EL PERFIL DE OTRO USUARIO, ni debe haberlo: sería una
 * superficie para recorrer la base de usuarios entera, incompatible con RNF-10.
 * Los datos públicos del interlocutor —nombre, foto, área— van embebidos en las
 * respuestas que los necesitan: el detalle de un chat, el panel de la protectora y
 * las alertas.
 */
@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;

    /**
     * GET /api/usuarios/me -> 200, 401
     *
     * Perfil completo del usuario autenticado, con su email: es el suyo, así que
     * aquí sí viaja. En cualquier respuesta que hable de OTRO usuario va
     * AutorResponse, que no lo lleva.
     */
    @GetMapping("/me")
    public ResponseEntity<UsuarioResponse> perfil(
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(usuarioService.perfil(usuario.getId()));
    }

    /**
     * PUT /api/usuarios/me -> 200, 400, 401
     *
     * Personaliza nombre, teléfono, área geográfica y foto (RF-02).
     *
     * NO CAMBIA EL EMAIL NI LA CONTRASEÑA. El email identifica la cuenta y es con
     * lo que se inicia sesión, así que cambiarlo desde aquí permitiría quedarse
     * con una cuenta ajena a partir de una sesión olvidada, sin conocer la
     * contraseña; hacerlo bien exige verificar la dirección nueva antes de
     * activarla, y ese flujo no está en RF-02. La contraseña tiene su propio
     * camino en RF-17, que además cierra las sesiones abiertas al cambiarla.
     *
     * Es un PUT y fija el perfil entero: los campos opcionales que no se envíen
     * quedan vacíos, que es como se borra un teléfono o una foto.
     */
    @PutMapping("/me")
    public ResponseEntity<UsuarioResponse> actualizarPerfil(
            @Valid @RequestBody ActualizarPerfilRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(usuarioService.actualizarPerfil(usuario.getId(), peticion));
    }

    /**
     * PUT /api/usuarios/me/idioma -> 200, 400, 401
     *
     * Cambia el idioma de la aplicación (RF-18). Admitidos: es, en, ca, gl, eu.
     *
     * ES UN PUT Y NO UN PATCH, en contra de lo que enunciaba el contrato: fija por
     * completo el valor del recurso "mi idioma" y repetirlo deja el mismo estado,
     * que es lo que un PUT promete. Es además el criterio que sigue el resto de la
     * API para las transiciones de un solo campo —cerrar una alerta, confirmar una
     * adopción, resolver un reporte—, todas PUT.
     *
     * Devuelve el perfil completo y no solo el idioma, para que el cliente pueda
     * repintar sin una segunda petición.
     */
    @PutMapping("/me/idioma")
    public ResponseEntity<UsuarioResponse> cambiarIdioma(
            @Valid @RequestBody CambiarIdiomaRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        return ResponseEntity.ok(
                usuarioService.cambiarIdioma(usuario.getId(), peticion.getIdioma()));
    }

    /**
     * DELETE /api/usuarios/me -> 204, 400, 401
     *
     * Da de baja la cuenta propia de forma irreversible (RF-19).
     *
     * PIDE LA CONTRASEÑA EN EL CUERPO, y por eso este DELETE lleva cuerpo pese a
     * que no sea lo habitual en ese verbo. Es la única operación irreversible de
     * la API: un token demuestra que alguien inició sesión en las últimas 24
     * horas, no que quien pulsa ahora sea el dueño, y un móvil desbloqueado basta
     * para lo primero. La alternativa —mandarla en la query— dejaría la contraseña
     * escrita en los registros del servidor (RNF-10).
     *
     * 401 si la contraseña no coincide: es el mismo hecho que un login fallido.
     *
     * NO BORRA NADA: anonimiza. La fila del usuario se conserva sin sus datos
     * personales y todo lo que apunta a ella —alertas, chats, mensajes,
     * valoraciones— se queda intacto, para que la otra parte de cada conversación
     * no pierda un histórico que también es suyo. Ver UsuarioService y §19.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> eliminarCuenta(
            @Valid @RequestBody EliminarCuentaRequest peticion,
            @AuthenticationPrincipal UsuarioPrincipal usuario) {

        usuarioService.eliminarCuenta(usuario.getId(), peticion.getPassword());
        return ResponseEntity.noContent().build();
    }
}
