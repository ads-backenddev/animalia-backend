# Contrato de la API REST

Backend de la plataforma de rescate y localización animal. Documento de referencia para
implementar los endpoints: **antes de escribir un endpoint, búscalo aquí** para confirmar método,
ruta, body y respuesta (ver la skill `nuevo-endpoint`).

- **Base URL:** `/api`
- **Formato:** JSON (`Content-Type: application/json; charset=UTF-8`)
- **Transporte:** HTTPS en todas las peticiones (RNF-04)

---

## 1. Convenciones generales

### Autenticación

Autenticación **stateless** con JWT (RF-13). Salvo en los endpoints marcados como `Público`, toda
petición debe llevar la cabecera:

```
Authorization: Bearer <token>
```

El token se obtiene en `POST /api/auth/login` y caduca a las 24 h (`jwt.expiration`).

### Roles

| Marca en la columna *Auth* | Significado |
|---|---|
| `Público` | No requiere token |
| `Autenticado` | Cualquier usuario con token válido |
| `CIUDADANO` | Rol CIUDADANO |
| `PROTECTORA` | Rol PROTECTORA con estado `ACTIVA` |
| `ADMIN` | Rol ADMIN |
| `Propietario` | Además del rol, el recurso debe pertenecer al usuario autenticado |

Los roles se aplican con `@PreAuthorize` en el controller, nunca comprobando el rol a mano dentro
del método.

### Códigos HTTP del sistema

| Código | Cuándo |
|---|---|
| `200 OK` | Consulta o actualización correcta |
| `201 Created` | Recurso creado; incluye cabecera `Location` |
| `204 No Content` | Operación correcta sin cuerpo de respuesta |
| `400 Bad Request` | Validación de entrada fallida, parámetros o token de restablecimiento inválido |
| `401 Unauthorized` | Falta el token, es inválido o ha caducado; credenciales incorrectas |
| `403 Forbidden` | Autenticado pero sin permiso: rol insuficiente, no es propietario, cuenta bloqueada o protectora no verificada |
| `404 Not Found` | El recurso no existe |
| `409 Conflict` | Email ya registrado (RF-01) o alerta duplicada en 500 m / 24 h (RF-25) |
| `429 Too Many Requests` | Límite diario de matches alcanzado (RF-24) |
| `500 Internal Server Error` | Error no previsto |

### Formato de error

Todas las excepciones se traducen en `exception/GlobalExceptionHandler`:

```json
{
  "timestamp": "2026-03-14T18:22:05.417Z",
  "status": 409,
  "error": "Conflict",
  "codigo": "ALERTA_DUPLICADA",
  "mensaje": "Ya existe una alerta similar en la zona en las últimas 24 horas",
  "ruta": "/api/alertas"
}
```

En errores de validación (`400`) se añade `errores`, con un par campo → motivo por cada campo
rechazado:

```json
{
  "status": 400,
  "codigo": "VALIDACION",
  "errores": { "email": "debe ser una dirección de email válida" }
}
```

### Paginación y ordenación

Los listados admiten los parámetros estándar de Spring Data: `page` (desde 0), `size` (por defecto
20) y `sort` (por ejemplo `sort=createdAt,desc`). La respuesta envuelve los resultados:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "last": true }
```

### Coordenadas

En la base de datos la geolocalización es un `Point` de PostGIS (SRID 4326). En la API viaja como
objeto anidado, y el service hace la conversión:

```json
{ "ubicacion": { "latitud": 40.4168, "longitud": -3.7038 } }
```

### Marcas de tiempo

Todas las marcas de tiempo de la API son **instantes en UTC**, en ISO-8601 y terminadas en `Z`:

```json
{ "createdAt": "2026-08-20T17:42:11.512Z" }
```

Esto vale tanto para las respuestas (`createdAt`, `fechaAvistamiento`, `expiraEn`, el `timestamp`
del cuerpo de error) como para lo que el cliente **envía**: `fechaAvistamiento` en
`POST /api/alertas` debe llevar zona. `"2026-08-20T19:42:11"` sin `Z` ni desfase se rechaza con
`400`.

> **Cambio de contrato.** Hasta el bloque de mantenimiento del esquema estos campos eran horas de
> pared sin zona (`"2026-08-20T19:42:11"`), que es lo que el cliente debía enviar y lo que recibía.
> Significaban cosas distintas según dónde corriera la API —el portátil de desarrollo va en
> `Europe/Madrid` y Railway en UTC—, y con ello la ventana de 24 h de RF-25 se desplazaba dos horas
> entre entornos y se encogía o estiraba en el cambio de hora. El cliente Android tiene que enviar
> y parsear con zona a partir de aquí.

La única excepción es **`reiniciaEn`**, del `429` por cuota diaria agotada (RF-24), que sigue siendo
una fecha-hora local sin zona. No es un instante sino una fecha de calendario: la cuota se cuenta
por día natural y "mañana a las 00:00" depende de qué calendario se mire. Ese calendario es el de
**`Europe/Madrid`**, fijado explícitamente en el servidor — con sus límites, en §6, *Estado del
límite diario*.

### Fotos (`fotoUrl`)

Las fotos se suben en dos pasos:

1. **`POST /api/fotos`** (autenticado, `multipart/form-data`, parte `archivo`) recibe el archivo y
   responde `201` con `{ "url": "https://<dominio>/fotos/<uuid>.jpg" }`, la misma URL en `Location`.
   Es el **único endpoint de la API que recibe archivos**.
2. El cliente pone esa URL en el campo `fotoUrl` del perfil, la alerta, el avistamiento o el animal.
   Esos endpoints siguen recibiendo JSON y no cambian.

Lo que se acepta en la subida:

- **JPEG o PNG, decidido por los bytes del archivo**, no por su nombre ni por el `Content-Type` que
  declare el cliente. Cualquier otra cosa, o una imagen que no se pueda decodificar, es `400`
  `FOTO_NO_VALIDA`.
- **5 MB como máximo.** Por encima, `413` `FOTO_DEMASIADO_GRANDE`.
- **25 subidas al día por usuario**, contadas por día natural de Madrid como el cupo de matches
  (RF-24). Pasado el cupo, `429` `LIMITE_FOTOS_ALCANZADO` con `limiteDiario` y `reiniciaEn`, el mismo
  cuerpo que el de matches (§6). Una subida rechazada con `400` no gasta cupo.
- **Se guarda recodificada, no tal como llega**: sin EXIF ni ningún otro metadato —tampoco las
  coordenadas GPS de donde se hizo la foto— y con el lado mayor reducido a 2048 px si lo supera.
  Como la orientación también es un dato EXIF, **el cliente debe enviar la imagen ya girada**.

Las fotos se sirven en **`GET /fotos/{nombre}`**, la URL que devolvió la subida. Es **público, sin
token**, porque una etiqueta `<img>` no puede mandar la cabecera `Authorization`; lo que impide
recorrerlas es que el nombre es un UUID aleatorio. Responde con `Cache-Control: public,
max-age=31536000, immutable` —un archivo no cambia nunca; una foto nueva lleva un nombre nuevo— y
`404` para cualquier nombre que no sea el de una foto existente. Una foto borrada (por ejemplo, la de
perfil de una cuenta anonimizada) deja de servirse en el mismo momento, aunque su archivo tarde en
desaparecer del disco.

**`fotoUrl` solo admite una URL devuelta por `POST /api/fotos` a quien la envía.** Cualquier otra
cosa devuelve `400` `VALIDACION` con `errores.fotoUrl`: una URL externa —aunque sea `https`—,
`javascript:`, `data:`, una ruta relativa, o la foto de **otro** usuario copiada de una respuesta
pública. Los motivos: una foto externa no se puede borrar en la baja de su dueño (RF-19) y deja al
servidor ajeno ver la IP de quien la carga; y una foto ajena haría que la baja de su dueño borrase
la imagen de quien la copió. Como la base pública es `https` en producción (el arranque falla si no),
toda `fotoUrl` aceptada allí es `https`.

Reenviar sin cambios la foto que ya tiene un perfil o un animal **no** se vuelve a comprobar: el
cliente manda el recurso entero al editarlo, y una foto anterior a `/api/fotos` no debe impedir
editar lo demás. Los campos opcionales siguen admitiendo `null`, y una cadena en blanco equivale a no
mandar foto (en `PUT /api/usuarios/me`, la quita).

**Una foto de perfil que se cambia o se quita deja de existir**, igual que al dar de baja la cuenta
(RF-19, ver §19): deja de servirse en el momento y su archivo se borra. Salvo que alguna publicación
del mismo usuario la use: entonces es la foto de un animal y se queda.

**Una foto subida que no se llega a usar se borra pasado un día.** Una limpieza diaria (a las 4:30,
hora de Madrid) quita las fotos de más de un día a las que no apunta ningún `fotoUrl` —el cliente
subió la imagen y nunca publicó—, junto con los archivos que quedaron en disco sin fila —un borrado
que falló— y las subidas interrumpidas a medias. El cliente debe subir la foto **justo antes** de
usarla, no guardarse la URL para más tarde: la primera limpieza tras cumplir 24 horas sin usarse la
borra, así que una subida sin usar dura entre uno y dos días.

Por qué se construyó así —y no con Firebase Storage—, su configuración y sus limitaciones: §21.

---

## 2. `/api/auth` — Registro, sesión y contraseña

RF-01, RF-13, RF-16, RF-17.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/auth/registro/ciudadano` | Registra un usuario con rol CIUDADANO. La contraseña se guarda con BCrypt (RNF-03) | Público | `RegistroCiudadanoRequest`: `nombre`, `email`, `password`, `telefono?`, `areaGeografica?`, `idioma?` | `UsuarioResponse` | `201`, `400`, `409`, `413`, `429` |
| `POST` | `/api/auth/registro/protectora` | Registra una protectora. Queda en estado `PENDIENTE` hasta que el administrador la verifica, y no puede iniciar sesión hasta entonces | Público | `RegistroProtectoraRequest`: `nombre`, `email`, `password`, `telefono?`, `nombreOrganizacion`, `direccion`, `zonaGeografica`, `esPersonaFisica?` | `ProtectoraResponse` con `estado: PENDIENTE` | `201`, `400`, `409`, `413`, `429` |
| `POST` | `/api/auth/login` | Autentica y devuelve el JWT. Debe responder en menos de 2 s (RNF-01) | Público | `LoginRequest`: `email`, `password` | `LoginResponse`: `token`, `tipo: "Bearer"`, `expiraEn`, `usuario` | `200`, `400`, `401`, `403`, `413`, `429` |
| `POST` | `/api/auth/logout` | Cierra la sesión del cliente e invalida el token en la lista de revocados | Autenticado | — | — | `204`, `401` |
| `POST` | `/api/auth/recuperar-password` | Envía al email registrado un enlace con token temporal (1 h). Responde `204` siempre, exista o no el email, para no revelar qué cuentas están registradas | Público | `RecuperarPasswordRequest`: `email` | — | `204`, `400`, `413`, `429` |
| `POST` | `/api/auth/restablecer-password` | Fija la nueva contraseña a partir del token del enlace | Público | `RestablecerPasswordRequest`: `token`, `nuevaPassword` | — | `204`, `400` |

**`403` en login:** cuenta bloqueada por el administrador (`activo = false`), con código
`CUENTA_BLOQUEADA`, o protectora en estado `PENDIENTE` / `RECHAZADA`, con `PROTECTORA_PENDIENTE` o
`PROTECTORA_RECHAZADA`. El bloqueo no espera a que caduque el token que el usuario ya tuviera: se
comprueba en cada petición, así que sus llamadas pasan a `401` en el acto (§11).

**`429 DEMASIADOS_INTENTOS`:** límite de intentos contra los tres endpoints donde se adivinan
contraseñas. Hay dos contadores independientes, los dos sobre una **ventana deslizante de 15
minutos**:

| Contador | Límite | Se reinicia |
|---|---|---|
| Por cuenta **y ruta** (el `email` del cuerpo, normalizado) | 5 | Solo tras un **login** correcto |
| Por dirección de origen | 30 | Nunca dentro de la ventana |

**El contador por cuenta es independiente en cada ruta, y solo un login correcto devuelve el cupo.**
Las dos cosas son necesarias por el mismo motivo: `recuperar-password` responde `204` siempre, exista o
no el email. Con un contador compartido y un reinicio disparado por cualquier `2xx`, bastaba intercalar
una solicitud de recuperación entre tanteos para borrar los intentos fallidos de login, y el límite no
saltaba nunca. Separarlos evita además el efecto contrario: que pedir cinco veces el enlace de
recuperación de otra persona le cierre el acceso.

La respuesta lleva la cabecera **`Retry-After`** con los segundos que faltan y el cuerpo de error
habitual. El corte ocurre en un filtro, **antes** del controller, así que un ataque no llega a gastar
BCrypt ni a consultar la base de datos. El mensaje es el mismo exista o no la cuenta, para no
convertir el 429 en un detector de cuentas registradas.

**`413 CUERPO_DEMASIADO_GRANDE`:** las tres rutas rechazan un cuerpo mayor de 16 KB. El filtro tiene
que leer el `email` del JSON antes de que llegue al controller, y cargar en memoria un cuerpo sin
límite en un endpoint público sería una forma cómoda de tumbar el servidor. El tope lo impone la
lectura, no la cabecera `Content-Length`: esa cifra la declara el cliente y vale `-1` cuando el cuerpo
viaja con `Transfer-Encoding: chunked`. Ningún cuerpo legítimo de estos endpoints pasa de unos cientos
de bytes.

Los tres valores se configuran con `app.auth.max-intentos-cuenta`, `app.auth.max-intentos-ip` y
`app.auth.ventana-intentos-minutos`. No están fijados en el código porque un despliegue detrás de una
NAT compartida —un instituto, una oficina— manda a mucha gente por la misma dirección y necesita subir
el tope por IP sin recompilar.

`POST /api/auth/restablecer-password` **no** se limita, y es deliberado: ver §17.

---

## 3. `/api/usuarios` — Perfil propio

RF-02, RF-18, RF-19.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/usuarios/me` | Perfil completo del usuario autenticado | Autenticado | — | `UsuarioResponse` | `200`, `401` |
| `PUT` | `/api/usuarios/me` | Personaliza el perfil: foto, nombre, área geográfica y teléfono | Autenticado | `ActualizarPerfilRequest`: `nombre`, `telefono?`, `areaGeografica?`, `fotoUrl?` | `UsuarioResponse` | `200`, `400`, `401` |
| `PUT` | `/api/usuarios/me/idioma` | Cambia el idioma de la aplicación (RF-18) | Autenticado | `CambiarIdiomaRequest`: `idioma` (`es`, `en`, `ca`, `gl`, `eu`) | `UsuarioResponse` | `200`, `400`, `401` |
| `DELETE` | `/api/usuarios/me` | Da de baja la cuenta propia de forma irreversible: la anonimiza, no la borra (RF-19, RNF-10) | Autenticado | `EliminarCuentaRequest`: `password` | — | `204`, `400`, `401` |

> **Cambio de contrato, dos.** El idioma pasa de `PATCH` a **`PUT`**: fija por completo el valor del
> recurso «mi idioma» y repetirlo deja el mismo estado, que es lo que un `PUT` promete y lo que ya
> hace el resto de transiciones de esta API. Y el `DELETE` **deja de arrastrar** alertas,
> avistamientos, matches, mensajes, valoraciones, reportes, notificaciones y contadores: ahora
> conserva todo eso y anonimiza la fila del usuario. El porqué está en §19.

**El `PUT /me` no cambia el email ni la contraseña**, y no es un olvido. RF-02 habla de personalizar
el perfil y no menciona ninguno de los dos. El email identifica la cuenta y es con lo que se inicia
sesión: cambiarlo desde aquí permitiría quedarse con una cuenta ajena a partir de una sesión
olvidada, sin conocer la contraseña, y hacerlo bien exige verificar la dirección nueva antes de
activarla —correo de confirmación, token, dirección pendiente—, que es un bloque en sí mismo. La
contraseña ya tiene su camino en RF-17, que además cierra las sesiones abiertas al cambiarla.

**El `DELETE` pide la contraseña en el cuerpo**, y por eso este verbo lleva cuerpo pese a que no sea
lo habitual. Es la única operación irreversible de la API: un token demuestra que alguien inició
sesión en las últimas 24 h, no que quien pulsa ahora sea el dueño, y un móvil desbloqueado sobre una
mesa basta para lo primero. Mandarla en la query la dejaría escrita en los registros del servidor
(RNF-10). Responde `401` si no coincide, el mismo código que un login fallido: es el mismo hecho.

**No hay endpoint para consultar el perfil de otro usuario.** Sería una superficie de enumeración de
toda la base de usuarios, incompatible con RNF-10. Los datos públicos del interlocutor (nombre, foto,
área) van embebidos en las respuestas que los necesitan: `ChatDetalleResponse`,
`PanelProtectoraResponse` y `AlertaResponse`.

---

## 4. `/api/alertas` — Mascotas perdidas

RF-03, RF-04, RF-14, RF-25.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/alertas` | Publica una alerta de mascota perdida. Antes de guardar comprueba si ya existe una alerta similar (RF-25) | Autenticado | `CrearAlertaRequest`: `descripcion`, `fotoUrl?`, `especie`, `fechaAvistamiento`, `ubicacion{latitud,longitud}`, `radioBusqueda?` | `AlertaResponse` | `201`, `400`, `401`, `409` |
| `GET` | `/api/alertas` | Listado paginado para navegar y buscar; el mapa en tiempo real sigue siendo `/mapa` y `/topic/mapa`. `estado`: `ACTIVA` por defecto o `ENCONTRADA`; `RETIRADA` y `ELIMINADA` no se listan (`400`). `especie` sin distinguir mayúsculas. Zona: `lat`, `lng`, `radio` (metros) — **los tres o ninguno**, 50 km como máximo, igual que `/mapa`. Paginación `page`/`size` (100 como máximo), más recientes primero | Autenticado | — | Página de `AlertaResponse` | `200`, `400`, `401` |
| `GET` | `/api/alertas/{id}` | Detalle con sus avistamientos `VISIBLE`. **`200` para cualquier alerta que haya existido**, en el estado que esté; `404` solo para un id que nunca existió. Una `RETIRADA` o `ELIMINADA` llega reducida (ver *Estados de una alerta*) | Autenticado | — | `AlertaDetalleResponse` | `200`, `401`, `404` |
| `PUT` | `/api/alertas/{id}` | Edita una alerta propia, **solo mientras esté `ACTIVA`**. Si cambian la especie o la ubicación vuelve a pasar RF-25, sin contarse a sí misma. Emite `ACTUALIZACION` por `/topic/mapa` | Propietario | `ActualizarAlertaRequest`: los mismos campos y reglas que la creación | `AlertaResponse` | `200`, `400`, `401`, `403`, `404`, `409` |
| `DELETE` | `/api/alertas/{id}` | La retira su dueño: pasa a `RETIRADA`, no se borra. Si estaba en el mapa emite `CIERRE`. Idempotente; sobre una `ELIMINADA` responde igual sin cambiarla | Propietario | — | — | `204`, `401`, `403`, `404` |
| `GET` | `/api/alertas/mapa` | Alertas `ACTIVA` con ubicación, para el mapa interactivo en tiempo real. Filtro por área: `lat`, `lng`, `radio` (metros) — **los tres o ninguno**; `radio` admite 50 km como máximo. Sin filtro devuelve todas las activas. Debe soportar 100 usuarios simultáneos (RNF-02) | Autenticado | — | Lista de `AlertaMapaResponse`: `id`, `especie`, `fotoUrl`, `ubicacion`, `fechaAvistamiento`, `estado` | `200`, `400`, `401` |
| `GET` | `/api/alertas/mis-alertas` | Alertas publicadas por el usuario autenticado, en cualquier estado y enteras, retiradas incluidas | Autenticado | — | Lista de `AlertaResponse` | `200`, `401` |
| `PUT` | `/api/alertas/{id}/cerrar` | Marca la mascota como encontrada: estado `ENCONTRADA`, se emite `CIERRE` por `/topic/mapa` y desaparece del mapa (RF-14). **Solo desde `ACTIVA`**; repetirlo sobre una `ENCONTRADA` no cambia nada, y sobre una `RETIRADA` o `ELIMINADA` es `400 ALERTA_NO_ACTIVA` | Propietario | — | `AlertaResponse` | `200`, `400`, `401`, `403`, `404` |

### Estados de una alerta

Solo `ACTIVA` está viva: es la única que sale en el mapa, admite avistamientos y deja hacer match sobre
ellos. Las otras tres son finales, y **en ninguna se borra nada**: los avistamientos, matches, chats y
mensajes que colgaban de la alerta siguen ahí, porque la conversación es también de la otra parte. Un
chat abierto sobre un avistamiento de una alerta retirada se sigue leyendo y sigue admitiendo
mensajes.

| Estado | Lo pone | Cómo | Detalle (`GET /{id}`) | Listado |
|---|---|---|---|---|
| `ACTIVA` | El autor, al publicarla | `POST /api/alertas` | Completo | Por defecto |
| `ENCONTRADA` | El autor, cuando aparece el animal (RF-14) | `PUT /{id}/cerrar` | Completo | Con `estado=ENCONTRADA` |
| `RETIRADA` | El autor, que la quita sin más | `DELETE /{id}` | Reducido | No |
| `ELIMINADA` | El administrador, por incumplir las normas (RF-11) | `DELETE /api/admin/publicaciones/{id}?tipo=ALERTA` | Reducido | No |

**`RETIRADA` y `ELIMINADA` son estados distintos a propósito.** Una es una decisión neutra del dueño
y la otra una sanción; si compartieran valor, la diferencia se perdería en cada fila sin forma de
reconstruirla (`V16`). Por la misma razón **nada de lo que haga el dueño sobrescribe una
`ELIMINADA`**: no la puede cerrar, ni editar, y retirarla responde `204` sin cambiarla. El
administrador, en cambio, sí puede pasar a `ELIMINADA` una alerta ya `RETIRADA` o `ENCONTRADA`.

**El detalle reducido** de una `RETIRADA` o `ELIMINADA` lleva solo `id`, `especie`, `estado` y
`createdAt`; el resto llega a `null` y `avistamientos` vacío. Responde `200` para que el cliente
distinga «esta alerta terminó» de «esta alerta nunca existió» (`404`), pero sin volver a publicar lo
que se retiró. Su autor la sigue viendo entera en `/mis-alertas`.

### Detección de alertas duplicadas (RF-25)

`POST /api/alertas` **bloquea** la publicación y responde `409` cuando ya existe una alerta que
cumple las tres condiciones a la vez:

1. misma `especie`,
2. `ultimaUbicacion` dentro de un radio de **500 m** (consulta espacial PostGIS `ST_DWithin`),
3. **publicada o avistada** en las **últimas 24 h**: su `createdAt` o su `fechaAvistamiento` caen
   dentro de la ventana.

La misma comprobación se repite al **editar** una alerta (`PUT /api/alertas/{id}`) cuando cambian su
especie o su ubicación, sin contarse a sí misma. Si cambian otros campos no se repite: ninguno de
ellos decide si esta alerta duplica a otra.

> **Cambio de contrato.** Hasta el bloque de los endpoints de alerta (§22) la tercera condición era
> solo «`fechaAvistamiento` en las últimas 24 h», y dejaba dos huecos. Una alerta publicada hoy sobre
> un animal visto hace dos días —el caso más común: se pierde el sábado y se publica el lunes— no
> bloqueaba nunca a nadie, así que dos vecinos podían publicar el mismo animal. Y al editar, atrasar
> esa fecha hacía que una alerta dejara de proteger su sitio. `createdAt` lo pone el servidor y no se
> edita, así que con las dos fechas no hay forma de esquivar la detección. A cambio, ahora se
> consideran duplicadas más alertas que antes.

La respuesta redirige al ciudadano a la alerta existente incluyéndola en el cuerpo:

```json
{
  "status": 409,
  "codigo": "ALERTA_DUPLICADA",
  "mensaje": "Ya existe una alerta similar en la zona en las últimas 24 horas",
  "alertaExistente": { "id": 42, "especie": "perro", "distanciaMetros": 180 }
}
```

---

## 5. `/api/avistamientos` — Avistamientos y feed de swipe

RF-08, RF-15, RF-21.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/avistamientos` | Registra un avistamiento sobre una alerta, con foto para la identificación visual del match (RF-21) | Autenticado | `CrearAvistamientoRequest`: `alertaId`, `descripcion`, `fotoUrl`, `ubicacion{latitud,longitud}` | `AvistamientoResponse` | `201`, `400`, `401`, `404` |
| `GET` | `/api/avistamientos/feed` | Feed para el swipe: avistamientos candidatos, excluyendo los propios y los ya deslizados por el usuario (RF-15) | Autenticado | — | Lista de `AvistamientoFeedResponse` | `200`, `401` |
| `GET` | `/api/avistamientos/{id}` | Detalle de un avistamiento. **Pendiente de implementar** | Autenticado | — | `AvistamientoResponse` | `200`, `401`, `404` |

`fotoUrl` es obligatoria aunque el modelo la admita nula: el swipe se decide comparando la foto del
animal perdido con la del avistado, así que una tarjeta sin imagen no sirve para nada (RF-21).

La alerta referenciada debe existir —si no, `404`— y estar en estado `ACTIVA`. Si ya se cerró, la
respuesta es `400` con código `ALERTA_NO_ACTIVA`: sobre un animal que ya apareció, un avistamiento
nuevo no aporta nada y solo ensuciaría el feed.

Cada tarjeta del feed lleva, además de los datos del avistamiento, la `alertaId`, la
`alertaEspecie` y la `alertaFotoUrl`, que son las que permiten la comparación visual.

> **Pendiente**: el feed va ordenado por fecha, más recientes primero, no por proximidad. Ordenar
> por cercanía exige saber dónde está el usuario, y ni la petición lo trae ni `USUARIO` guarda
> coordenadas —`areaGeografica` es texto—. Queda decidir de dónde sale ese punto.

Para consultar los avistamientos de una alerta concreta no hay endpoint propio: van incluidos en
`AlertaDetalleResponse` de `GET /api/alertas/{id}` (§4): los `VISIBLE`, más recientes primero, con los
datos públicos de su autor. Los `ELIMINADO` por el administrador no salen, y una alerta retirada o
moderada no enseña ninguno.

---

## 6. `/api/matches` — Matches y límite diario

RF-08, RF-09, RF-15, RF-24.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/matches` | Registra un deslizamiento. Con `interesado: true` es un match: incrementa el contador diario, abre el chat asociado (RF-12) y avisa a quien publicó lo deslizado. Con `false` es un descarte (RF-15) | Autenticado | `CrearMatchRequest`: `publicacionId`, `tipoPublicacion` (`AVISTAMIENTO` \| `ADOPCION`), `interesado` | `MatchResponse`: `id`, `publicacionId`, `tipoPublicacion`, `interesado`, `chatId?`, `createdAt` | `201`, `400`, `401`, `403`, `404`, `409`, `429` |
| `GET` | `/api/matches/mios` | Matches del usuario autenticado, más recientes primero. Solo los positivos: los descartes comparten tabla pero no son matches | Autenticado | — | Lista de `MatchResponse` | `200`, `401` |
| `GET` | `/api/matches/limite` | Estado del límite diario, para que el cliente lo muestre antes de agotarlo | Autenticado | — | `LimiteMatchResponse` | `200`, `401` |

### Los dos sentidos del swipe van por el mismo endpoint

Un solo `POST /api/matches` cubre las dos direcciones, distinguiéndose por `interesado`, igual que
se distinguen en la tabla `MATCH_REGISTRO`:

| `interesado` | Significado | Abre chat | Notifica | Consume cuota diaria |
|---|---|---|---|---|
| `true` | Match (RF-08) | Sí | Sí, `NUEVO_MATCH` al autor del avistamiento | Sí |
| `false` | Descarte (RF-15) | No | No | No |

**El `NUEVO_MATCH` va al autor del avistamiento, no al dueño de la alerta.** Son los dos extremos del
chat que el match abre (§7): quien desliza y quien publicó lo deslizado. El dueño de la alerta es
normalmente quien desliza —el feed solo le esconde sus propios avistamientos, no los que otros
registran sobre su alerta—, así que notificarle a él era avisar a quien acababa de hacer el match.

`interesado` es obligatorio y no tiene valor por defecto: dejarlo implícito convertiría un cuerpo
incompleto en un match involuntario. `chatId` solo viaja en la respuesta cuando hay match; en un
descarte se omite del JSON en lugar de ir a `null`.

Como el descarte no consume cuota, **sigue funcionando con el límite diario agotado**: solo se
bloquean los matches.

### Reglas de acceso y conflicto

**`403` con código `PUBLICACION_PROPIA`** si el usuario intenta deslizar contenido suyo, tanto para
hacer match como para descartarlo. No es un `400`: la petición está bien formada y la publicación
existe, lo que falla es el permiso. Un match sobre lo propio abriría además un chat de un usuario
consigo mismo. El feed ya excluye las publicaciones propias, así que esto cubre la llamada directa.

**`409` con código `SWIPE_DUPLICADO`** si el usuario ya había deslizado esa publicación, en
cualquiera de los dos sentidos. Lo garantiza la restricción única
`(usuario_id, publicacion_id, tipo_publicacion)`, que es la misma que permite a los feeds de
`/api/avistamientos/feed` y `/api/adopcion/feed` excluir lo ya visto.

**`400` con código `ALERTA_NO_ACTIVA`** si la alerta de la que cuelga el avistamiento ya se cerró,
en cualquiera de los dos sentidos del deslizamiento. El feed filtra por alerta `ACTIVA`, pero el
dueño puede cerrarla mientras el cliente tiene la tarjeta en pantalla; sin esta comprobación se
abriría un chat y se avisaría al dueño por un animal que ya apareció, gastando además un match del
día. Es el mismo código que devuelve `POST /api/avistamientos` sobre una alerta cerrada (§5).

**`400` con código `ANIMAL_NO_DISPONIBLE`** si el animal en adopción deslizado ya no está
`DISPONIBLE`, sea porque está `ADOPTADO` o porque la protectora lo `RETIRADO` del catálogo. Es el
equivalente de `ALERTA_NO_ACTIVA` en el otro tipo de publicación: la tarjeta pudo mostrarse antes de
que la protectora lo cambiara, y sin esta comprobación se abriría una conversación sobre algo ya
cerrado gastando un match del día. El código es el mismo en los dos casos y el `mensaje` los
distingue, porque para quien lo lee no significan lo mismo: `ADOPTADO` es un final feliz y
`RETIRADO` puede ser cualquier cosa.

El orden de las comprobaciones importa: la cuota se consume en último lugar, después de validar
todo lo demás. Al revés, un swipe repetido —que acaba en `409`— le habría gastado al usuario un
match del día. Dentro de la validación de la publicación, `PUBLICACION_PROPIA` se comprueba antes
que el estado: a quien desliza algo suyo ya cerrado se le dice que es suyo, no que está cerrado.

### Los dos tipos de publicación

`tipoPublicacion` decide en qué tabla vive `publicacionId` —es una referencia polimórfica sin clave
ajena, así que el mismo id existe en las dos— y, con ella, quién es el otro extremo del chat que
abre el match:

| `tipoPublicacion` | Publicación | Otro participante del chat y destinatario del aviso | Estado que la inhabilita |
|---|---|---|---|
| `AVISTAMIENTO` | `AVISTAMIENTO` (RF-08) | `AVISTAMIENTO.usuario`, el autor del avistamiento | Su alerta no está `ACTIVA` |
| `ADOPCION` | `ANIMAL_ADOPCION` (RF-09) | `ANIMAL_ADOPCION.protectora.usuario`, la cuenta de la protectora | El animal no está `DISPONIBLE` (`ADOPTADO` o `RETIRADO`) |

En los dos casos la regla del aviso es la misma: se notifica a quien **no** deslizó. En adopción eso
significa que una protectora recibe el `NUEVO_MATCH` cuando un ciudadano muestra interés por uno de
sus animales, y que una protectora no puede deslizar los suyos —lo rechaza `PUBLICACION_PROPIA`,
igual que cualquier otro contenido propio—. El resto del recorrido es común a los dos tipos: cuota
diaria, restricción única y apertura del chat no distinguen de qué publicación se trate.

### Estado del límite diario

`GET /api/matches/limite` devuelve:

```json
{
  "limiteDiario": 20,
  "consumidos": 3,
  "restantes": 17,
  "reiniciaEn": "2026-03-15T00:00:00"
}
```

`restantes` nunca es negativo. El contador no se reinicia con ninguna tarea programada: al cambiar
la fecha simplemente se empieza una fila nueva en `LIMITE_MATCH_DIARIO`, de ahí que `reiniciaEn`
sea siempre la medianoche del día siguiente. El límite se configura con
`app.matches.limite-diario` (20 por defecto).

**El día de la cuota es el de `Europe/Madrid`**, fijado explícitamente en `MatchService` y no
heredado de la zona de la máquina. Sin fijarlo, la cuota se reiniciaba a una hora distinta en cada
entorno: en Railway, que va en UTC, a la 01:00 o a las 02:00 de la madrugada peninsular según la
época del año, y en el portátil de desarrollo a medianoche. Con la zona fija, esas `00:00` son
medianoche peninsular en todas partes.

Es una **simplificación consciente**, no una solución general. Vale para España peninsular, que es
el alcance geográfico del proyecto, y **no es correcta para Canarias**: allí se va una hora por
detrás, así que un usuario canario ve su cuota reiniciarse a las 23:00 de su noche. Hacerlo bien
exige que el cliente envíe su zona y que se guarde junto al contador, o contar la cuota por instante
en vez de por día natural; es una decisión de producto de RF-24 que no se ha tomado, y queda
anotada como pendiente en §16.

**`429` al alcanzar el límite (RF-24)**, con mensaje informativo y momento de reinicio:

```json
{
  "status": 429,
  "codigo": "LIMITE_MATCHES_ALCANZADO",
  "mensaje": "Has alcanzado el límite de 20 matches diarios. Podrás continuar mañana.",
  "limiteDiario": 20,
  "reiniciaEn": "2026-03-15T00:00:00"
}
```

---

## 7. `/api/chats` — Chat en tiempo real

RF-12.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/chats` | Conversaciones del usuario, con último mensaje y número de no leídos | Autenticado | — | Lista de `ChatResumenResponse` | `200`, `401` |
| `GET` | `/api/chats/{id}` | Detalle de una conversación: interlocutor y publicación que la originó | Participante | — | `ChatDetalleResponse` | `200`, `401`, `403`, `404` |
| `GET` | `/api/chats/{id}/mensajes` | Histórico paginado de mensajes, del más reciente al más antiguo | Participante | — | Página de `MensajeResponse` | `200`, `401`, `403`, `404` |
| `POST` | `/api/chats/{id}/mensajes` | Envía un mensaje. Alternativa REST al WebSocket, para clientes sin conexión STOMP activa | Participante | `EnviarMensajeRequest`: `contenido` | `MensajeResponse` | `201`, `400`, `401`, `403`, `404` |
| `PUT` | `/api/chats/{id}/mensajes/leidos` | Marca como leídos todos los mensajes recibidos en la conversación | Participante | — | — | `204`, `401`, `403`, `404` |

`Participante` significa que el usuario autenticado es una de las dos partes del match que generó el
chat; en caso contrario `403`. Quién es la segunda parte depende del tipo de publicación deslizada
(§6): el autor del avistamiento, o la cuenta de la protectora dueña del animal en adopción.

`ChatDetalleResponse` lleva `tipoPublicacion` y, según su valor, **uno** de los dos grupos de campos
de contexto; el otro se omite del JSON:

| `tipoPublicacion` | Campos presentes |
|---|---|
| `AVISTAMIENTO` | `avistamientoId`, `avistamientoFotoUrl`, `avistamientoDescripcion`, `alertaId`, `alertaEspecie`, `alertaFotoUrl`, `alertaEstado` |
| `ADOPCION` | `animalId`, `animalNombre`, `animalEspecie`, `animalFotoUrl`, `animalEstado`, `protectoraId`, `protectoraNombre` |

Si la alerta está `RETIRADA` o `ELIMINADA`, el chat sigue funcionando pero `alertaFotoUrl` se omite:
se mantienen `alertaId`, `alertaEspecie` y `alertaEstado`, el mismo recorte que hace el detalle de
la alerta (§4). Los campos del avistamiento no cambian: son del otro participante, y la retirada o la
moderación de la alerta no los alcanza.

Un chat cuya publicación ya no existe se omite del listado en vez de tumbarlo entero, y responde
`404` al pedir su detalle, que es donde ese chat concreto es lo que se ha pedido. Retirar un animal
del catálogo ya no provoca ese caso: desde que `DELETE /api/adopcion/{id}` lo deja en `RETIRADO` en
lugar de borrar la ficha (§8), la conversación se mantiene y el `animalEstado` del detalle dice qué
pasó. El filtro se conserva porque nada en el esquema impide que una fila desaparezca —
`publicacion_id` no tiene clave ajena—, y sin él una sola fila borrada a mano tumbaría el listado
completo de alguien.

El marcado de leídos es `PUT` y no `PATCH` porque no modifica un subconjunto de campos de un
recurso: fija por completo el estado «todo lo recibido está leído», y repetirlo deja la conversación
exactamente igual. Solo alcanza a los mensajes del otro participante —nadie es destinatario de sí
mismo—, así que los propios nunca se tocan.

El envío en vivo va por el destino STOMP `/app/chat/{chatId}/enviar`, con difusión a
`/topic/chat/{chatId}` (§13). `POST /api/chats/{id}/mensajes` es la alternativa para clientes sin
conexión STOMP: guarda y difunde por el mismo canal, de modo que el otro participante lo recibe en
vivo aunque el emisor no esté suscrito.

---

## 8. `/api/adopcion` — Catálogo de adopción

RF-07, RF-09, RF-20, RF-22.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/adopcion` | Catálogo paginado de animales `DISPONIBLE`. Filtros de RF-22: `especie`, `tamanyo` (`PEQUENO` \| `MEDIANO` \| `GRANDE`), `zona` | Autenticado | — | Página de `AnimalAdopcionResponse` | `200`, `400`, `401` |
| `GET` | `/api/adopcion/{id}` | Detalle de un animal con los datos de su protectora. `protectoraDireccion` se omite si la protectora es una persona física (§9) | Autenticado | — | `AnimalAdopcionDetalleResponse` | `200`, `401`, `404` |
| `GET` | `/api/adopcion/feed` | Feed para el swipe de adopciones, excluyendo los propios y los ya deslizados (RF-09, RF-15). Admite los mismos filtros que el catálogo | Autenticado | — | Lista de `AnimalAdopcionResponse` | `200`, `400`, `401` |
| `POST` | `/api/adopcion` | Publica un animal en adopción | `PROTECTORA` | `CrearAnimalRequest`: `nombre`, `especie`, `tamanyo`, `descripcion`, `fotoUrl`, `zonaGeografica` | `AnimalAdopcionResponse` | `201`, `400`, `401`, `403` |
| `PUT` | `/api/adopcion/{id}` | Modifica un animal de la propia protectora | `PROTECTORA` + Propietario | `ActualizarAnimalRequest`: mismos campos que la creación | `AnimalAdopcionResponse` | `200`, `400`, `401`, `403`, `404` |
| `DELETE` | `/api/adopcion/{id}` | Retira un animal del catálogo: pasa a estado `RETIRADO`. **No borra la ficha** | `PROTECTORA` + Propietario | — | — | `204`, `401`, `403`, `404` |
| `PUT` | `/api/adopcion/{id}/adoptado` | Confirma la adopción: el animal pasa a estado `ADOPTADO` (RF-20) | `PROTECTORA` + Propietario | — | `AnimalAdopcionResponse` | `200`, `400`, `401`, `403`, `404` |

### Estados de un animal

| Estado | Significado | En catálogo y feed | Detalle |
|---|---|---|---|
| `DISPONIBLE` | Se ofrece en adopción. Es donde nace todo animal publicado | Sí | Sí |
| `ADOPTADO` | La protectora confirmó la adopción (RF-20) | No | Sí |
| `RETIRADO` | La protectora lo quitó del catálogo | No | Sí |

`DELETE /api/adopcion/{id}` **conserva la fila** y solo cambia el estado, igual que cerrar una alerta
la deja en `ELIMINADA`. Sigue siendo un `DELETE` con `204` porque desde fuera es lo mismo: el animal
deja de estar en el catálogo. Lo que cambia es lo que le pasa a quien tenía una conversación abierta
sobre él. Borrando la fila, esa conversación desaparecía de su listado sin explicación —los matches
apuntan a `publicacion_id` sin clave ajena que los proteja, así que quedaban huérfanos con su chat y
sus mensajes—; conservándola, el chat sigue en pie y el otro extremo ve que el animal está
`RETIRADO`.

Es idempotente: retirar dos veces deja el mismo estado y responde `204` igual. Y es terminal, como
`ADOPTADO`: no hay vuelta a `DISPONIBLE`, un animal que vuelve a ofrecerse se publica de nuevo.

**`400` con código `ANIMAL_RETIRADO`** al confirmar la adopción de un animal `RETIRADO`. El camino
`DISPONIBLE → RETIRADO → ADOPTADO` afirmaría dos cosas incompatibles —que se sacó del catálogo y que
se adoptó—, así que se corta.

### Permisos: el rol no basta

Los cuatro endpoints de gestión exigen **dos** cosas, y solo la primera cabe en una anotación:

- **El rol `PROTECTORA`**, que impone `@PreAuthorize`. Un `CIUDADANO` recibe `403` con código
  `ACCESO_DENEGADO` sin que la petición llegue al service.
- **Ser la protectora dueña de ese animal**, que depende del recurso pedido y resuelve el service.
  Una protectora que toca el animal de otra recibe `403` con código `ANIMAL_AJENO`.

Es `403` y no `404` porque el animal existe y el catálogo es público entre autenticados: `GET
/api/adopcion/{id}` ya confirma su existencia, así que ocultarlo aquí no escondería nada. Un animal
que de verdad no existe sigue siendo `404`.

`403` con código `SIN_PROTECTORA` cubre una cuenta con rol `PROTECTORA` cuya ficha de protectora no
existe. No debería ocurrir —el registro crea las dos juntas— pero sin la comprobación saldría un
`500`.

### Filtros del catálogo (RF-22)

Los tres son opcionales y **combinables entre sí**: se aplican en la misma consulta, de modo que
`?especie=perro&tamanyo=PEQUENO&zona=Madrid` devuelve la intersección. Un filtro vacío
(`?especie=`) se trata como filtro ausente.

| Filtro | Comparación |
|---|---|
| `especie` | Igualdad sin distinguir mayúsculas: es texto libre que escribe la protectora |
| `tamanyo` | Valor exacto del enum; cualquier otro valor da `400` |
| `zona` | Contiene, sin distinguir mayúsculas: `Madrid` encuentra `Madrid, Carabanchel` |

El catálogo devuelve **solo animales `DISPONIBLE`**; el estado no es un filtro que el cliente pueda
cambiar. Un animal `ADOPTADO` o `RETIRADO` desaparece del listado pero conserva su detalle, para quien llegue
desde un enlace o desde un chat abierto antes de la adopción.

### Feed de swipe (RF-09, RF-15)

`GET /api/adopcion/feed` es el catálogo menos lo que este usuario no debe volver a ver:

| Se excluye | Por qué |
|---|---|
| Los animales de su propia protectora | Nadie hace match consigo mismo; `POST /api/matches` lo rechazaría con `403 PUBLICACION_PROPIA` |
| Lo que ya deslizó, en cualquiera de los dos sentidos | Es lo que registra `MATCH_REGISTRO`. Sin esta exclusión un descarte reaparecería en la siguiente sesión, y el segundo swipe acabaría en `409 SWIPE_DUPLICADO` |
| Los que no están `DISPONIBLE` | Como en el catálogo: un `ADOPTADO` ya tiene casa y un `RETIRADO` ya no se ofrece |

La exclusión de lo deslizado filtra **por tipo además de por id**: `publicacion_id` es una referencia
polimórfica, así que sin ese filtro haber deslizado el avistamiento 7 escondería del feed al animal
7, que no tiene nada que ver con él.

Admite `especie`, `tamanyo` y `zona` con la misma semántica que el catálogo: quien busca un perro
pequeño en su zona quiere deslizar sobre eso, no sobre todo lo publicado.

**Devuelve una lista, no una página.** El feed se consume tarjeta a tarjeta, así que ni el cliente
pide páginas ni le sirve saber cuántas quedan; el servidor manda una tanda acotada
(`app.adopcion.tamanyo-feed`, 30 por defecto), igual que `GET /api/avistamientos/feed`. Un feed
agotado es `200` con lista vacía, no `404`.

### Confirmación de la adopción (RF-20)

Es `PUT` y no `PATCH` por el mismo motivo que `/api/chats/{id}/mensajes/leidos` (§7): no modifica un
subconjunto de campos sino que fija por completo el estado del animal, y repetirlo lo deja igual. No
lleva cuerpo: `ANIMAL_ADOPCION` no tiene dónde guardar quién adoptó, así que un `usuarioAdoptanteId`
sería un dato que se recibe y se tira.

La transición es de ida: no hay vuelta a `DISPONIBLE`. Si una adopción se cae, lo que corresponde es
publicar el animal de nuevo, para no dejar los matches del ciclo anterior colgando de una ficha que
ya no describe el mismo caso. Editar un animal ya adoptado sí se permite —corregir una foto o una
descripción no cambia su disponibilidad—, pero el `PUT` de edición no lleva el estado en el cuerpo,
así que tampoco puede reabrirlo por esa vía.

---

## 9. `/api/protectoras` — Directorio, contacto y valoración

RF-06, RF-10, RF-23.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/protectoras` | Directorio de protectoras publicables, con su valoración y su contacto. Filtro `zona`, paginado | Autenticado | — | Página de `ProtectoraDirectorioResponse` | `200`, `400`, `401` |
| `GET` | `/api/protectoras/{id}` | Ficha de una protectora: su contacto, sus animales `DISPONIBLE` paginados y sus valoraciones más recientes | Autenticado | — | `ProtectoraDetalleResponse` | `200`, `400`, `401`, `404` |
| ~~`POST`~~ | ~~`/api/protectoras/{id}/contactar`~~ | **Diseñado, no construido.** Abrir un contacto con una protectora al encontrar un animal perdido | Autenticado | `ContactarProtectoraRequest` | `ChatResumenResponse` | — |
| ~~`POST`~~ | ~~`/api/protectoras/local/contactar`~~ | **Diseñado, no construido.** Contactar con la protectora de la localidad resolviéndola desde `areaGeografica` | Autenticado | `ContactarProtectoraRequest` | `ChatResumenResponse` | — |
| `PUT` | `/api/protectoras/{id}/valoracion` | Crea o actualiza la valoración de 1 a 5 estrellas de quien ha tratado con la protectora (RF-23) | Autenticado | `ValorarProtectoraRequest`: `puntuacion` (1-5) | `ValoracionResponse` | `200`, `400`, `401`, `403`, `404` |
| `GET` | `/api/protectoras/panel/animales` | Todos los animales de la protectora autenticada, **en cualquier estado** (RF-10). Paginado | `PROTECTORA` `ACTIVA` | — | Página de `AnimalAdopcionResponse` | `200`, `401`, `403` |
| `GET` | `/api/protectoras/panel/solicitudes` | Ciudadanos que han querido ponerse en contacto por alguno de sus animales (RF-10) | `PROTECTORA` `ACTIVA` | — | Lista de `SolicitudAdopcionResponse` | `200`, `401`, `403` |
| `GET` | `/api/protectoras/panel/valoraciones` | Valoraciones recibidas, con la media calculada (RF-10, RF-23) | `PROTECTORA` `ACTIVA` | — | `ValoracionesPanelResponse`: `valoraciones[]`, `media?`, `total` | `200`, `401`, `403` |
| `GET` | `/api/protectoras/panel/resumen` | Contadores agregados para la pantalla principal del panel (RF-10) | `PROTECTORA` `ACTIVA` | — | `ResumenPanelResponse` | `200`, `401`, `403` |

> **Cambio de contrato.** Valorar era `POST` con `201` y un `409` si ya se había valorado. Ahora es
> **`PUT` idempotente con `200`**, y el `409` desaparece. El motivo es que un usuario tiene como mucho
> **una** valoración sobre una protectora: RF-23 habla de "la valoración" en singular, y volver a
> enviarla es cambiar de opinión, no emitir un segundo voto. Con `POST`/`409`, corregir una nota
> exigía un endpoint de modificación que nunca se diseñó, o borrar y volver a crear. El recurso "mi
> valoración de esta protectora" tiene una única URL que no cambia, así que no hay `Location` nuevo
> que anunciar y `201` no aportaba nada. La unicidad la respalda `UNIQUE (usuario_id, protectora_id)`
> desde la migración `V9`.

**`403 SIN_INTERACCION_PREVIA` en valoración:** RF-23 pide una valoración *"tras una adopción o
contacto"*, no una valoración libre. El criterio implementado es tener al menos un **deslizamiento a
favor sobre un animal en adopción de esa protectora**, que es lo que abre el chat con ella (RF-12) y
por tanto lo que constituye el "contacto". Se eligió eso y no la adopción consumada porque el
requisito dice "adopción **o** contacto", y exigir la adopción dejaría sin voz justo a quien contactó
y no adoptó, que es la experiencia que más interesa conocer. Sin esa condición, cualquiera podría
puntuar a cualquier protectora sin haber tratado con ella, y un puñado de cuentas nuevas bastaría para
hundir una reputación.

**La media no se guarda en ninguna parte.** `PROTECTORA.valoracion_media` era una columna derivada que
ningún código mantenía —nula para todas, indefinidamente— y se eliminó en `V9`. La media se calcula
con `AVG(puntuacion)` y `COUNT(*)` donde se lee: en el panel, en el detalle de un animal en adopción y
en el directorio de RF-06. Así es correcta por construcción y no puede desincronizarse.

Los tres la piden al **mismo** `ValoracionService`, y eso importa más que ahorrarse un `AVG`: RF-06 y
RF-07 publican la reputación de la misma protectora, y calcularla por separado en dos sitios es la
forma de que un día no coincidan. Una prueba de integración pide los dos endpoints seguidos y compara
los dos números contra PostgreSQL. El listado usa una **agregación en lote** —una consulta agrupada
para toda la página, no dos por fila— apoyada en el índice `idx_valoracion_protectora` de `V10`.

> **Cambio de contrato en el directorio.** El listado devolvía `ProtectoraResponse` y el detalle tenía
> al lado un `GET /api/protectoras/{id}/animales` propio. Al construirlo cambiaron las dos cosas.
> `ProtectoraResponse` arrastra dentro un `UsuarioResponse` entero —rol, idioma, `activo`, área
> geográfica—, y de todo eso un listado público necesita dos campos: se ha sustituido por
> `ProtectoraDirectorioResponse` y `ProtectoraDetalleResponse`, que enumeran campo a campo lo que
> exponen. Y los animales viajan **dentro** de la ficha, paginados por sus propios `page` y `size`, que
> es lo único de esa respuesta que puede crecer sin límite; el endpoint aparte era una segunda llamada
> para pintar la misma pantalla.

**Solo se publican las `ACTIVA`, y además con la cuenta en pie.** Una protectora `PENDIENTE` no está
verificada y una `RECHAZADA` no va a estarlo (RF-11); publicarlas sería avalar ante el ciudadano a
alguien de quien la plataforma no responde. No salen en el listado y **su ficha responde `404`, no
`403`**, también pidiéndola por su id. Es una excepción consciente al criterio del resto de la API
—en el chat y en el catálogo un recurso ajeno que existe da `403`— porque aquí lo que no debe poder
averiguarse recorriendo ids es precisamente que la fila está ahí. El motivo real queda en el log.

La segunda condición apareció construyendo esto: **la cuenta tiene que estar activa**. Una protectora
que se da de baja (RF-19) conserva su `estado = ACTIVA` —la baja anonimiza al usuario y retira sus
animales, pero no toca la fila de `PROTECTORA`— y una bloqueada por el administrador tampoco lo
cambia. Sin mirar `USUARIO.activo`, el directorio habría publicado la ficha de una protectora que ya
no existe ofreciendo como contacto su email anonimizado `@cuenta-eliminada.invalid`.

Qué estados entran lo decide un **switch exhaustivo sin rama por defecto**, y hay uno solo: el listado
no repite la condición en SQL, **deriva de él** su lista de estados recorriendo el enum. Un valor nuevo
en `EstadoProtectora` rompe la compilación en lugar de colarse en el directorio por omisión. Una
prueba recorre el enum entero comparando lo que deja pasar la ficha con lo que pide el listado.

### El contacto de una protectora sí se expone. El de un ciudadano no.

Es la diferencia de criterio más llamativa de la API y es **deliberada**, no una inconsistencia con lo
que se decidió en el bloque 7.

En todo el resto de la API el email y el teléfono de un usuario no salen nunca: alertas,
avistamientos, matches, valoraciones y solicitudes del panel llevan `AutorResponse` —`id`, `nombre`,
`fotoUrl`— porque son datos personales y el modelo no tiene ningún campo con el que alguien declare
que quiere compartirlos (RNF-10). El canal por el que un ciudadano los comparte si quiere es el chat.

Aquí salen por dos motivos que allí no se dan. El primero, que **RF-06 lo pide literalmente**: el
directorio se consulta *«incluyendo su información de contacto»*, y sin ella el requisito no se cumple
y el endpoint queda en una lista de nombres con la que no se puede hacer nada. El segundo, que **una
protectora no es un particular**: se da de alta precisamente para ser encontrada y contactada, y su
email es la dirección con la que atiende adopciones. Negárselo sería protegerla de lo que ha venido a
hacer.

**El teléfono es la excepción de la excepción.** Ese razonamiento vale entero para una asociación
registrada, cuyo email y teléfono son los que publica en su propia web. No vale igual para el caso que
`V12` obligó a reconocer: una protectora que es **una persona particular** rescatando por su cuenta
(`esPersonaFisica = true`), cuyo teléfono es su móvil personal. La línea se traza entre los dos
canales:

| Dato | Entidad | Persona física | Por qué |
|---|---|---|---|
| `contacto.email` | Sí | **Sí** | Es el identificador de la cuenta, es asíncrono —se lee cuando se puede, se filtra, se ignora— y sin él RF-06 no se cumple para toda una clase de protectoras. No es gratis: es el precio mínimo de estar en un directorio |
| `contacto.telefono` | Sí | **No** | Un móvil es directo e inmediato, no se filtra, y es el identificador más fuerte para cruzar con otras fuentes. Además es **opcional** en el modelo, así que pudo rellenarse para que el administrador verificara la solicitud (RF-11), no para un listado público |
| `direccion` (solo en la ficha) | Sí | **No** | Para un particular, la «dirección» es su domicilio. `V12` ya decidió que es dato personal al hacer que la baja lo borre; sería incoherente tratarlo como tal para borrarlo y no para publicarlo |

El coste de equivocarse **no es simétrico**: ocultar el teléfono molesta a quien quería que le
llamaran, enseñarlo expone un número personal de forma irreversible. Entre esas dos se elige la
primera. Los campos ocultos **se omiten del JSON** en vez de viajar como `null`, para que «es un
particular» y «no dio teléfono» se vean igual desde fuera.

`esPersonaFisica` **viaja en la respuesta** a propósito: le dice al cliente a quién va a escribir —no
es lo mismo dirigirse a una asociación que a alguien que rescata por su cuenta— y por qué esa ficha
puede venir sin teléfono, que si no parecería un fallo.

**Nada de esto se aplica a quien valora.** Las valoraciones de la ficha llevan `AutorResponse`, sin
email ni teléfono: la excepción de RF-06 es el contacto de la **protectora**, no el de los ciudadanos
que han tratado con ella.

**El listado va ordenado por nombre**, que es una excepción al orden por defecto de esta API. El resto
de listados van por fecha descendente porque son «lo último que ha pasado»; un directorio es una lista
de referencia que se recorre buscando a alguien concreto, y con fecha descendente las protectoras
veteranas —las que más animales y más valoraciones tienen— quedarían en la última página para siempre.
Se ordena por `lower(nombre)` para no depender de la intercalación de la base de datos, con el `id`
como desempate.

**La ficha solo enseña los animales `DISPONIBLE`.** Es la misma distinción deliberada que ya separa el
catálogo público del panel: la ficha es un escaparate y `/panel/animales` el histórico de gestión. Un
animal ya adoptado no es una oferta, y listarlo llevaría a solicitudes sobre animales que ya tienen
casa. Las valoraciones de la ficha van **acotadas a las diez más recientes**, con `totalValoraciones`
diciendo cuántas hay de verdad: se sirve en cada visita al directorio, y quien las quiere todas es la
propia protectora, que las tiene en `/panel/valoraciones`.

> **El contacto con la protectora local sigue sin construir.** Las dos filas tachadas de la tabla
> —`/{id}/contactar` y `/local/contactar`— son contrato de diseño, no código. No es un olvido de este
> bloque: abrir un chat exige hoy un `MATCH_REGISTRO`, que a su vez exige una publicación deslizada, y
> un «contacto con la protectora» no cuelga de ninguna. Construirlo pide una decisión de modelo —hacer
> `CHAT.match_id` nulable, o inventar una publicación implícita— que no cabía aquí. Ver §20.

### Panel de la protectora (RF-10)

> **Cambio de contrato.** Hasta este bloque el panel era un único `GET /api/protectoras/panel` que
> devolvía un `PanelProtectoraResponse` con todo dentro. Se ha sustituido por las cuatro rutas de
> arriba: los tres listados crecen de forma independiente y el agregado obligaba a traerlos enteros
> para pintar unas cifras. `/panel/resumen` ocupa el lugar de aquel agregado, con contadores en vez
> de listas, y `totalAdopciones` es ahora `animalesAdoptados`.

**Todo el panel es sobre uno mismo.** Ninguna de las cuatro rutas admite un id de protectora: la
protectora se resuelve del token. Pedir el panel de otra no es algo que se rechace, es algo que no
se puede expresar.

**No basta con el rol.** `PROTECTORA` lo tiene igual una protectora `PENDIENTE` o `RECHAZADA` —el
rol se asigna al registrarse y la verificación es posterior (RF-11)—. Las cuatro rutas exigen además
estado `ACTIVA` y responden `403` con `PROTECTORA_PENDIENTE` o `PROTECTORA_RECHAZADA`, los mismos
códigos y mensajes que el login. Se comprueba **en cada petición** y no solo al entrar, porque un
token dura horas: una protectora que entra estando `ACTIVA` y a la que el administrador rechaza a
continuación seguiría operando hasta que caducara. `SIN_PROTECTORA` cubre el caso de una cuenta con
el rol pero sin fila de protectora asociada.

Solo se concede el acceso con estado `ACTIVA`, comprobado con un `switch` exhaustivo sobre
`EstadoProtectora`: si algún día RF-11 añade un estado intermedio, romperá la compilación en vez de
abrir el panel por omisión.

> **Ojo al implementar el cliente.** `SIN_PROTECTORA` es el único de los tres códigos que **no**
> aparece en el login: `POST /api/auth/login` trata esa misma cuenta rota como `PROTECTORA_PENDIENTE`.
> El panel la llama por su nombre, igual que hace `POST /api/adopcion`, porque decirle al usuario que
> espere a un administrador cuando lo que falta es una fila lo deja esperando algo que no va a llegar.
> Unificar el login con estos dos queda pendiente.

**`/panel/animales` no es el catálogo público.** `GET /api/adopcion` solo enseña los `DISPONIBLE`
porque es un escaparate; el panel enseña también los `ADOPTADO` y los `RETIRADO`, porque es el
histórico de gestión de la protectora. Es la diferencia deliberada entre los dos listados.

**`/panel/solicitudes` nunca incluye descartes.** Solo los `MATCH_REGISTRO` de tipo `ADOPCION` con
`interesado = true`. Los descartes de RF-15 comparten tabla y contarlos diría que alguien quiere un
animal al que en realidad dijo que no. Cada solicitud trae el chat abierto por el match, que es el
canal para responder (RF-12).

Los datos del ciudadano viajan como `AutorResponse` —`id`, `nombre`, `fotoUrl`—, **nunca su email ni
su teléfono**. No es una omisión temporal: el modelo no tiene ningún campo con el que un usuario
declare que quiere compartir su contacto, así que no hay forma de saber si lo ha consentido y
RNF-10 no permite suponerlo. El canal por el que el ciudadano los comparte si quiere es el chat.

**`/panel/valoraciones` ya devuelve datos reales**: `PUT /api/protectoras/{id}/valoracion` existe
desde el bloque 9 y alimenta la tabla. El endpoint no hizo falta tocarlo — su consulta ya era real y
su media ya se calculaba sobre las valoraciones—, solo faltaba que alguien pudiera escribir.

La media se calcula sobre las valoraciones reales y **no** salía ya de `PROTECTORA.valoracion_media`,
columna que además se eliminó en `V9` (§18). Se omite del JSON cuando no hay ninguna, en vez de
viajar como `0`: un cero se leería como la peor nota posible. Es el mismo criterio, y el mismo número,
que expone `GET /api/adopcion/{id}`.

**`/panel/resumen`** devuelve `animalesDisponibles`, `animalesAdoptados`, `animalesRetirados`,
`animalesTotales`, `solicitudesPendientes`, `solicitudesTotales`, `valoracionMedia?` y
`totalValoraciones`. Todo son agregaciones: no se trae ni un animal ni una solicitud a memoria.

Una solicitud cuenta como **pendiente** mientras su animal siga `DISPONIBLE`. RF-10 no lo define y
`MATCH_REGISTRO` no tiene ningún estado, así que hubo que elegir criterio: en cuanto el animal pasa a
`ADOPTADO` o `RETIRADO` el caso está cerrado, con adopción o sin ella, aunque su chat siga vivo. El
criterio alternativo —"sin responder en el chat"— mide la diligencia contestando, no los casos
abiertos, y además dependería de `MENSAJE`.

```json
{
  "animalesDisponibles": 4,
  "animalesAdoptados": 3,
  "animalesRetirados": 2,
  "animalesTotales": 9,
  "solicitudesPendientes": 5,
  "solicitudesTotales": 11,
  "valoracionMedia": 4.50,
  "totalValoraciones": 6
}
```

**En `/local/contactar`:** `400` si el usuario no tiene `areaGeografica` fijada en su perfil, `404` si
no hay ninguna protectora `ACTIVA` en esa zona. El segmento literal `local` tiene prioridad sobre el
patrón `{id}`, así que ambas rutas conviven sin ambigüedad.

---

## 10. `/api/reportes` — Reportes de contenido

RF-26.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/reportes` | Reporta una publicación o un usuario indicando el motivo. Queda en estado `PENDIENTE` para su revisión | Autenticado | `CrearReporteRequest`: `tipoObjetivo` (`PUBLICACION` \| `USUARIO`), `objetivoId`, `tipoContenido?`, `motivo` | `ReporteResponse` | `201`, `400`, `401`, `404` |

Valores de `motivo`: `CONTENIDO_INAPROPIADO`, `SPAM`, `INFORMACION_FALSA`, `ACOSO`,
`ANIMAL_EN_PELIGRO`, `OTRO`.

**Sin cabecera `Location`**, a diferencia del resto de `201` de esta API: `Location` anuncia dónde
leer lo recién creado, y un reporte no tiene ninguna URL donde su autor pueda leerlo — solo se
consulta desde `/api/admin/reportes`, que exige rol `ADMIN`. Dar esa dirección a quien acaba de
reportar sería darle una que le devuelve `403`.

**Reportar está abierto a cualquier autenticado, sin más requisitos**, y es lo contrario del criterio
de RF-23. La diferencia está en qué pasa si uno se equivoca: una valoración falsa altera una
reputación pública en el acto, mientras que un reporte no publica ni retira nada, solo pone el caso
delante de un administrador. Poner barreras a avisar de un animal en peligro sería el error caro.

**Nada se retira automáticamente por acumular reportes.** Un umbral automático convertiría el botón de
reportar en un arma: bastaría organizar unas cuantas cuentas para tumbar la publicación de un
competidor. La retirada la decide una persona con `DELETE /api/admin/publicaciones/{id}` (§11).

> **Cambio de contrato.** Desaparece el `409` por reportar dos veces el mismo objetivo. Exigirlo
> obligaría a una restricción de unicidad sobre `(usuario_id, tipo_objetivo, objetivo_id)` y a decidir
> qué hacer con un segundo reporte por un motivo distinto —ver primero un `SPAM` y después un
> `ANIMAL_EN_PELIGRO` sobre lo mismo es información, no ruido—. Queda anotado como pendiente al final
> de esta sección; hoy los duplicados se admiten.

**`400`** cuando `tipoContenido` no encaja con `tipoObjetivo`, y cuando alguien intenta reportarse a
sí mismo (código `AUTORREPORTE`).

**`tipoContenido` es obligatorio cuando `tipoObjetivo` es `PUBLICACION`** y debe omitirse cuando es
`USUARIO`. Valores: `ALERTA`, `AVISTAMIENTO`, `ADOPCION`. Sin él, `objetivoId` sería ambiguo: hay tres
tablas de publicaciones y el id solo es único dentro de cada una. Incumplirlo devuelve `400`.

---

## 11. `/api/admin` — Administración

RF-11. **Todos los endpoints requieren rol `ADMIN`.**

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/admin/usuarios` | Listado paginado de usuarios, más recientes primero. Filtros opcionales y combinables: `rol`, `activo` | `ADMIN` | — | Página de `UsuarioAdminResponse` | `200`, `400`, `401`, `403` |
| `PUT` | `/api/admin/usuarios/{id}/bloquear` | Bloquea la cuenta (`activo = false`): deja de poder iniciar sesión. Si es una protectora, **retira además sus animales `DISPONIBLE`** (§20) | `ADMIN` | `BloquearUsuarioRequest`: `motivo` (obligatorio, ≤500) | `UsuarioAdminResponse` | `200`, `400`, `401`, `403`, `404` |
| `PUT` | `/api/admin/usuarios/{id}/desbloquear` | Reactiva la cuenta | `ADMIN` | — | `UsuarioAdminResponse` | `200`, `401`, `403`, `404` |
| `GET` | `/api/admin/protectoras/pendientes` | Cola de verificación: protectoras `PENDIENTE`, más antiguas primero | `ADMIN` | — | Lista de `ProtectoraAdminResponse` | `200`, `401`, `403` |
| `PUT` | `/api/admin/protectoras/{id}/aprobar` | Verifica la protectora: pasa a `ACTIVA` y ya puede iniciar sesión, publicar y abrir su panel | `ADMIN` | — | `ProtectoraAdminResponse` | `200`, `401`, `403`, `404` |
| `PUT` | `/api/admin/protectoras/{id}/rechazar` | Rechaza la solicitud, o retira una verificación ya concedida: pasa a `RECHAZADA`. Si estaba `ACTIVA`, **retira además sus animales `DISPONIBLE`** (§20) | `ADMIN` | `RechazarProtectoraRequest`: `motivo` (obligatorio, ≤500) | `ProtectoraAdminResponse` | `200`, `400`, `401`, `403`, `404` |
| `GET` | `/api/admin/reportes` | Reportes `PENDIENTE`, más antiguos primero | `ADMIN` | — | Lista de `ReporteResponse` | `200`, `401`, `403` |
| `PUT` | `/api/admin/reportes/{id}/resolver` | Marca el reporte como `REVISADO` y avisa a quien lo emitió | `ADMIN` | — | `ReporteResponse` | `200`, `401`, `403`, `404` |
| `DELETE` | `/api/admin/publicaciones/{id}?tipo={tipo}` | Retira una publicación que incumple las normas (RF-11) | `ADMIN` | — | — | `204`, `400`, `401`, `403`, `404` |

> **Cambio de contrato.** Este bloque sustituye el borrador de arriba en cinco puntos. Las cuatro
> transiciones de estado son `PUT` y no `PATCH`, por lo mismo que `PUT /api/adopcion/{id}/adoptado`:
> fijan un estado y repetirlas lo dejan igual. Devuelven `200` con el recurso ya actualizado en vez de
> `204`, para que el cliente pinte la fila sin una segunda petición. Los dos `motivo` son obligatorios,
> no opcionales. El filtro `email` de `GET /api/admin/usuarios` se descarta: buscar por email es
> superficie de enumeración de RNF-10, y `rol` + `activo` cubren el trabajo real. Y
> `PATCH /api/admin/reportes/{id}/revisado` llegó en el bloque 9 como
> `PUT /api/admin/reportes/{id}/resolver` (ver más abajo).

### El rol es toda la comprobación, y va en la clase

`@PreAuthorize("hasRole('ADMIN')")` está escrito **una sola vez, a nivel de clase**, no repetido en
cada método. Con la anotación ocho veces, un noveno endpoint que naciera sin ella quedaría abierto a
cualquier autenticado sin que nada lo delatara; en la clase, todo lo que se añada al panel nace
protegido.

A diferencia del panel de protectora (§9) **no hay una segunda comprobación en el service**, y es
deliberado: el administrador no tiene un subconjunto de datos «suyos» sobre el que operar, su ámbito
es la plataforma entera. La única regla añadida es el autobloqueo.

**`403 AUTOBLOQUEO`: nadie puede bloquear su propia cuenta.** Es la única puerta sin retorno del
panel —una cuenta bloqueada no inicia sesión, así que un administrador que se bloqueara perdería el
acceso y con él la capacidad de desbloquearse, y solo saldría de ahí tocando la base de datos a
mano—. Es `403` y no `400`: la petición está bien formada y el rol es el correcto, lo que se rechaza
es la operación sobre ese objetivo, igual que `PUBLICACION_PROPIA` en §6.

**Bloquear y desbloquear son idempotentes**, y aprobar y rechazar también: repetir la decisión
devuelve el mismo recurso, no cambia nada y **no vuelve a notificar**. Un segundo aviso de algo que
el usuario ya sabía sería ruido.

**El bloqueo surte efecto en el acto, sin esperar a que caduque ningún token.** `AuthService` rechaza
el login de una cuenta inactiva con `403 CUENTA_BLOQUEADA` (§2), y `JwtAuthFilter` recarga el usuario
en **cada** petición, así que el token que el bloqueado tuviera ya emitido deja de autenticar y sus
peticiones pasan a `401`. Lo mismo vale para rechazar una protectora activa: su panel se comprueba en
cada petición y se le cierra al momento con `403 PROTECTORA_RECHAZADA`.

**Se aprueba también desde `RECHAZADA`, y se rechaza también desde `ACTIVA`.** Lo primero porque un
rechazo no es una condena: la protectora puede aportar la documentación que faltaba, y sin ese camino
su única salida sería registrarse de nuevo perdiendo la cuenta y todo lo que colgara de ella. Lo
segundo es retirar una verificación concedida cuando la organización deja de merecerla. Las dos
transiciones se deciden con un `switch` exhaustivo sobre `EstadoProtectora` **sin rama por defecto**,
el mismo patrón que §9: si algún día se añade un estado, romperá la compilación en vez de tomar una
decisión silenciosa sobre un caso que nadie pensó.

### Notificaciones de las decisiones administrativas

| Acción | Tipo | `referenciaId` |
|---|---|---|
| Bloquear | `CUENTA_BLOQUEADA` | *(nulo)* |
| Desbloquear | `CUENTA_DESBLOQUEADA` | *(nulo)* |
| Aprobar protectora | `PROTECTORA_VERIFICADA` | id de la protectora |
| Rechazar protectora | `PROTECTORA_RECHAZADA` | id de la protectora |

Los tres tipos nuevos se añadieron al enum `TipoNotificacion` con su migración `V6` (§16). La
referencia va nula en las dos de cuenta porque el aviso es sobre la cuenta del propio destinatario, no
sobre otra fila a la que apuntar.

**El `motivo` no llega al usuario, y conviene saberlo.** Hoy acaba solo en el log del servidor, junto
al id del administrador que decidió: `NOTIFICACION` no tiene ningún campo de texto —solo `tipo` y
`referencia_id`—, así que el afectado recibe un `CUENTA_BLOQUEADA` sin explicación. Se exige
igualmente en el body porque obliga a articular la decisión y porque el día que exista dónde
guardarlo el contrato ya lo pide. Hacérselo llegar exige una columna de texto en `NOTIFICACION` o una
tabla de auditoría de acciones del administrador, y ninguna de las dos está decidida.

Si la notificación falla, **la decisión se mantiene**: se crea en su propia transacción
(`REQUIRES_NEW`) y el error se registra sin propagarse. La decisión del administrador vale por sí
sola; el aviso es su consecuencia. Es el mismo trato que `AlertaService` da al aviso a la zona.

### `DELETE /api/admin/publicaciones/{id}` retira, no borra

**El parámetro `tipo` es obligatorio** y toma los valores del enum `TipoContenido`: `ALERTA`,
`AVISTAMIENTO` o `ADOPCION`. Sin él la petición devuelve `400`, porque el id de una publicación solo
es único dentro de su propia tabla —el 7 existe a la vez como alerta, como avistamiento y como
animal— y `{id}` por sí solo no identifica el recurso. Es el mismo par `(tipo, id)` que guarda
`REPORTE`, así que el administrador puede pasar directamente lo que le llega de `GET /api/admin/reportes`.
No se reutiliza `TipoPublicacion` porque ese enum solo cubre los tipos sobre los que se puede hacer
match, donde `ALERTA` no es válido.

El verbo es `DELETE`, pero **ninguna fila desaparece**: la publicación pasa a su estado terminal y
deja de mostrarse, conservando los chats que colgaran de ella. Es el mismo criterio que
`DELETE /api/adopcion/{id}` y `DELETE /api/alertas/{id}`, y es idempotente.

**Una alerta moderada queda `ELIMINADA`, distinta de la `RETIRADA` por su dueño** (§4, *Estados de
una alerta*), y el dueño no puede deshacerlo ni cerrándola ni retirándola. Si estaba en el mapa,
**se emite `CIERRE` por `/topic/mapa`**: antes del bloque de los endpoints de alerta (§22) no se
hacía, y el marcador de una alerta moderada seguía en el mapa de quien lo tuviera abierto.

| `tipo` | Tabla | Estado terminal |
|---|---|---|
| `ALERTA` | `ALERTA` | `ELIMINADA` |
| `AVISTAMIENTO` | `AVISTAMIENTO` | `ELIMINADO` *(nuevo, `EstadoAvistamiento`)* |
| `ADOPCION` | `ANIMAL_ADOPCION` | `RETIRADO` |

**Por qué `AVISTAMIENTO` también necesitaba un estado, y no valía el borrado real.** Era el único de
los tres que no tenía ninguno, así que había que decidirlo. Un `DELETE` físico habría sido peor aquí
que en las otras dos tablas: `MATCH_REGISTRO.publicacion_id` apunta al avistamiento con una referencia
polimórfica **sin clave ajena** (§16), y `ChatRepository.buscarPorParticipante` resuelve el autor de
la publicación con un `EXISTS` contra `AVISTAMIENTO`. Borrar la fila no da error en ninguna parte:
simplemente el chat deja de aparecer en la lista de su dueño, en silencio, sin que la base de datos
pueda impedirlo. Con el estado, el chat sigue vivo y solo desaparece la publicación del feed. La
migración es `V7` (§16).

Para el animal se reutiliza `RETIRADO`, el mismo estado con el que una protectora saca un animal de su
catálogo, en vez de inventar uno para la moderación: la consecuencia es idéntica —deja de ofrecerse— y
quién lo retiró queda en el log. Un estado más solo añadiría una rama que ninguna consulta necesita
distinguir.

**Retirar una publicación no notifica a nadie.** `REPORTE_RESUELTO` va dirigido a quien reportó, no al
autor de lo retirado, y se emite al resolverlo, no al retirar: aquí no hay reporte del que colgarlo.

### La cola de reportes, y qué significa resolver uno

`GET /api/admin/reportes` ya devuelve datos reales: `POST /api/reportes` existe desde el bloque 9 y la
alimenta. Se sirve **más antiguos primero** porque es una cola de trabajo, y lo que lleva más tiempo
esperando es lo que toca — el mismo criterio que la cola de protectoras pendientes.

**`REVISADO` significa "atendido", no "tenía razón".** Resolver un reporte es sacarlo de la cola y esa
es toda su semántica: el administrador ya lo ha mirado. Si además hay que retirar la publicación, eso
es `DELETE /api/admin/publicaciones/{id}`, una decisión aparte y deliberadamente separada, para que
revisar no arrastre consecuencias que nadie ha pedido. Por eso `EstadoReporte` tiene dos valores y no
tres: no hace falta distinguir "aceptado" de "desestimado" para vaciar la cola, y fingir esa
distinción obligaría a mantenerla coherente con lo que se haya hecho por el otro lado.

Es **`PUT` y no `PATCH`** —como enumeraba §15— porque fija por completo el estado del reporte, igual
que el resto de transiciones de esta API, y es idempotente: resolver dos veces deja lo mismo y **no
vuelve a notificar**.

**Se avisa a quien reportó, no a quien fue reportado**, con `REPORTE_RESUELTO`. Al reportado no se le
dice nada: un reporte revisado y desestimado no es un hecho que le concierna, y comunicárselo
convertiría cada denuncia infundada en un aviso de que alguien la presentó.

**La cola no enseña a quién se reporta**, solo el par `(tipoObjetivo, objetivoId)` más `tipoContenido`
cuando es una publicación. Con eso el administrador puede encadenar `DELETE /api/admin/publicaciones`,
que recibe exactamente ese par. Un reporte es una acusación sin revisar, y devolver los datos del
acusado a quien liste la cola —o a quien lo denuncia, en la respuesta del `POST`— convertiría el
endpoint en una forma cómoda de sacarle el contacto a alguien con quien se ha discutido.

---

## 12. `/api/notificaciones` — Notificaciones

RF-05.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/notificaciones` | Notificaciones del usuario, más recientes primero. Filtro `leida` | Autenticado | — | Página de `NotificacionResponse` | `200`, `401` |
| `PATCH` | `/api/notificaciones/{id}/leida` | Marca una notificación como leída | Propietario | — | — | `204`, `401`, `403`, `404` |

Tipos de notificación: `ALERTA_CERCANA`, `NUEVO_MATCH`, `NUEVO_MENSAJE`, `PROTECTORA_VERIFICADA`,
`ADOPCION_CONFIRMADA`, `REPORTE_RESUELTO`, y desde RF-11 `PROTECTORA_RECHAZADA`, `CUENTA_BLOQUEADA` y
`CUENTA_DESBLOQUEADA` (§11, migración `V6`).

Ninguna lleva texto: `NOTIFICACION` solo guarda `tipo` y `referencia_id`, así que el cliente compone
el mensaje a partir del tipo. Es lo que impide que el `motivo` de un bloqueo llegue al afectado (§11).

**Una notificación puede apuntar a algo que no existe** (limitación aceptada). Los avisos derivados
—`ALERTA_CERCANA` al publicar una alerta y los de las decisiones del administrador (§11)— se crean en
su propia transacción (`REQUIRES_NEW`), que se confirma **antes** que la operación que los origina.
Si esa operación fallara justo al confirmarse, el aviso quedaría guardado y su `referencia_id`
apuntaría a una alerta que nunca llegó a existir. Es remoto —el `INSERT` de la alerta ya se ha
ejecutado antes de avisar, así que solo queda que falle el propio commit— y nunca apunta a otra
alerta, porque un id consumido no se reutiliza. Se cerraría creando el aviso tras la confirmación
(`afterCommit`), como ya se hace con el evento del mapa.

**Cómo lo distingue el cliente.** Al abrir un aviso `ALERTA_CERCANA`, `GET /api/alertas/{id}` (§4)
responde `200` con su `estado` para cualquier alerta que haya existido —también una `ENCONTRADA`,
`RETIRADA` o `ELIMINADA`, que llegan reducidas— y `404 NO_ENCONTRADO` **solo** si nunca existió. Así
que un `200` con un estado final es un aviso viejo de algo que ya terminó, y un `404` es un aviso
huérfano que el cliente descarta en silencio. Las alertas no caducan ni se borran, de modo que una
que terminó con normalidad nunca da `404`.

> **RF-05 sigue sin implementar** —uno de los dos huecos que quedan, ver §20—. Las dos filas de esta tabla son
> contrato de diseño, no código: `NotificacionController` es un esqueleto vacío. Lo que **sí** existe
> es la mitad de escritura —`NotificacionService` persiste notificaciones y los nueve tipos están en la
> tabla con su `CHECK` (`V6`)—, así que hoy se guardan y nadie puede leerlas. Falta el `GET`, el
> `PATCH` de marcado y la emisión por `/user/queue/notificaciones`, que hoy **no la envía nadie**: no
> hay ni una llamada a ese destino en todo el código. Ver §20.

---

## 13. Canales WebSocket (STOMP)

Hay **un único endpoint** de conexión, `/ws`; lo que distingue a cada canal es el destino STOMP al
que el cliente se suscribe. El JWT viaja en el handshake, en la cabecera `Authorization: Bearer` o
en el parámetro `token` —esta segunda vía existe porque la API WebSocket de los navegadores no
permite añadir cabeceras—. Sin token válido el servidor responde `401` y no llega a abrirse la
conexión: lo comprueba `JwtHandshakeInterceptor`.

```
ws://<host>/ws?token=<jwt>     prefijo de suscripción: /topic, /queue
                               prefijo de envío:       /app
```

| Destino | Dirección | Descripción | Auth | Payload |
|---|---|---|---|---|
| `/topic/mapa` | Servidor → cliente | Altas, cambios de estado y cierres de alertas para refrescar el mapa sin recargar (RF-04). Debe soportar 100 usuarios simultáneos (RNF-02) | Autenticado | `EventoMapaResponse`: `accion` (`ALTA` \| `ACTUALIZACION` \| `CIERRE`) + `alerta` (`AlertaMapaResponse`) |
| `/topic/chat/{chatId}` | Bidireccional (envío a `/app/chat/{chatId}/enviar`) | Mensajería en tiempo real de una conversación (RF-12) | Participante del chat | Envío: `EnviarMensajeRequest`. Recepción: `MensajeResponse` |
| `/user/queue/errores` | Servidor → cliente | Rechazos de un envío por WebSocket, devueltos solo a la sesión que los provocó | Autenticado | `ErrorResponse` |
| `/user/queue/notificaciones` | Servidor → cliente | Notificaciones del usuario, incluidas las alertas registradas en su área geográfica (RF-05). **Pendiente de implementar**: hoy las notificaciones solo se persisten, y `GET /api/notificaciones` tampoco existe todavía | Autenticado | `NotificacionResponse` |

El prefijo `/user/` no es una ruta más: Spring lo resuelve contra el `Principal` de la sesión STOMP
y entrega el mensaje **solo** a la conexión de ese usuario, aunque todos se suscriban literalmente a
`/user/queue/notificaciones`. El servidor envía con
`convertAndSendToUser(usuario, "/queue/notificaciones", …)`.

Ese `Principal` lo pone `UsuarioHandshakeHandler`, que sobrescribe `determineUser` para convertir en
principal de la sesión al `UsuarioPrincipal` que `JwtHandshakeInterceptor` ya había validado en el
handshake. Sin él bastaría para `/topic/mapa` —difusión a cualquier autenticado— pero no para
`/user/…`, que no sabría a qué conexión entregar.

Suscribirse a `/topic/chat/{chatId}` está además restringido a los participantes: el destino es
público entre autenticados y los ids son secuenciales, así que sin filtro bastaría probar números
para escuchar conversaciones ajenas. Lo comprueba `ChatSuscripcionInterceptor` en el `SUBSCRIBE`, y
rechaza igual el chat ajeno que el inexistente —en un canal STOMP no hay `403` frente a `404` que
distinguir—.

**Cada `SUBSCRIBE` revalida la sesión**, sea al destino que sea, antes de decidir nada sobre él: que
el token no haya caducado, que no se haya revocado con un *logout*, que la cuenta siga activa y que
el token no sea anterior al último cambio de contraseña. Son las mismas comprobaciones que
`JwtAuthFilter` hace en cada petición REST, y hacen falta porque el handshake ocurre **una sola vez**:
sin ellas una conexión abierta seguiría admitiendo suscripciones nuevas durante horas después de que
el usuario cerrara sesión o un administrador lo bloqueara. Los errores son `Tu sesión ha caducado`,
`Tu sesión ya no es válida` y `Tu cuenta ha sido bloqueada por un administrador`, y llegan por
`/user/queue/errores`. **Lo que esto no hace es cerrar la conexión ni cancelar las suscripciones ya
hechas**: ver la limitación conocida en §17.

**No se admiten suscripciones con comodín.** El bróker no reparte comparando cadenas: usa un
`AntPathMatcher`, así que un `SUBSCRIBE` a `/topic/chat/*` o a `/topic/**` recibiría todo lo que se
publique en `/topic/chat/{chatId}`, de cualquier conversación. Como ningún destino documentado aquí
lleva comodín, se rechaza cualquiera que lo contenga. Dentro de `/topic/chat` la regla es **denegar
por defecto**: un destino que no sea exactamente `/topic/chat/<número>` se rechaza en vez de dejarlo
pasar por no haberlo reconocido.

### `/user/queue/errores`

Un envío a `/app/chat/{chatId}/enviar` no tiene respuesta HTTP donde devolver un rechazo, así que
los errores del canal viajan por esta cola personal. El cuerpo es el mismo `ErrorResponse` de la API
REST (§1), para que el cliente no tenga que interpretar dos formatos distintos; `ruta` lleva el
destino STOMP que falló en lugar de la ruta HTTP.

| Situación | `status` | `codigo` |
|---|---|---|
| El texto está vacío o supera los 2000 caracteres | `400` | `VALIDACION` (con `errores` campo a campo) |
| El chat existe pero el usuario no participa, o la sesión no está autenticada | `403` | `CHAT_AJENO` |
| El chat no existe | `404` | `NO_ENCONTRADO` |
| Fallo no controlado | `500` | `ERROR_INTERNO` |

Existe porque una excepción sin manejar en un `@MessageMapping` hace que Spring emita una trama
`ERROR` de STOMP, y una trama `ERROR` **cierra la conexión**: un mensaje demasiado largo tumbaría el
chat entero en vez de rechazarse solo. El aviso se entrega únicamente a la sesión que lo originó
(`broadcast = false`), no a las demás del mismo usuario. Como en REST, el texto de un `500` no sale
al cliente (RNF-10): se responde «Se ha producido un error inesperado» y el detalle queda en el log.

---

## 14. Trazabilidad requisito → endpoint

| RF | Dónde se cubre |
|---|---|
| RF-01 | `POST /api/auth/registro/ciudadano`, `POST /api/auth/registro/protectora` |
| RF-02 | `GET` y `PUT /api/usuarios/me`; la foto se sube antes por `POST /api/fotos` (§1) |
| RF-03 | `POST /api/alertas`; consultarla, editarla y retirarla: `GET /api/alertas`, `GET`, `PUT` y `DELETE /api/alertas/{id}` (§4) |
| RF-04 | `GET /api/alertas/mapa`, destino `/topic/mapa` |
| RF-05 | `GET /api/notificaciones`, destino `/user/queue/notificaciones` — **diseñado, sin construir** (§20) |
| RF-06 | `GET /api/protectoras` y `GET /api/protectoras/{id}` — directorio, ficha y contacto (§9) |
| RF-07 | `POST`, `PUT` y `DELETE` sobre `/api/adopcion` |
| RF-08 | `POST /api/matches` con `tipoPublicacion=AVISTAMIENTO` |
| RF-09 | `GET /api/adopcion/feed`, `POST /api/matches` con `tipoPublicacion=ADOPCION` |
| RF-10 | `GET /api/protectoras/panel/animales`, `/solicitudes`, `/valoraciones`, `/resumen` |
| RF-11 | Todo `/api/admin` |
| RF-12 | `/api/chats/**`, destino `/topic/chat/{chatId}` |
| RF-13 | `POST /api/auth/login` |
| RF-14 | `PUT /api/alertas/{id}/cerrar` |
| RF-15 | `POST /api/matches` con `interesado: false`, `GET /api/avistamientos/feed`, `GET /api/adopcion/feed` |
| RF-16 | `POST /api/auth/logout` |
| RF-17 | `POST /api/auth/recuperar-password`, `POST /api/auth/restablecer-password` |
| RF-18 | `PUT /api/usuarios/me/idioma` |
| RF-19 | `DELETE /api/usuarios/me` — anonimiza, no borra, salvo la foto de perfil, que sí se borra (§19, §21) |
| RF-20 | `PUT /api/adopcion/{id}/adoptado` |
| RF-21 | `POST /api/avistamientos` (campo `fotoUrl`, subida antes por `POST /api/fotos`) |
| RF-22 | `GET /api/adopcion` (filtros `especie`, `tamanyo`, `zona`) |
| RF-23 | `PUT /api/protectoras/{id}/valoracion`; media en `GET /api/adopcion/{id}` y en `/panel/valoraciones` |
| RF-24 | `GET /api/matches/limite`, respuesta `429` de `POST /api/matches` |
| RF-25 | Respuesta `409` de `POST /api/alertas`, y de `PUT /api/alertas/{id}` si cambian la especie o la ubicación |
| RF-26 | `POST /api/reportes`, `GET /api/admin/reportes`, `PUT /api/admin/reportes/{id}/resolver` |

---

## 15. Endpoints añadidos sobre el resumen de partida

El resumen inicial enumeraba los recursos y sus rutas principales. Estos endpoints se añadieron para
cubrir requisitos que, si no, se quedaban sin ruta. **Revisados y confirmados**, con las decisiones de
modelo que hicieron falta para sostenerlos:

| Endpoint | Motivo |
|---|---|
| Campo `interesado` en `POST /api/matches` | RF-15 (swipe de descarte) no tenía ruta en el resumen. Se resolvió con un campo en el endpoint existente, no con una ruta aparte: match y descarte son el mismo gesto en dos direcciones y comparten tabla, restricción única y validaciones (§6) |
| `GET /api/adopcion/feed` | RF-09 + RF-15: el swipe de adopción necesita su propio feed, igual que el de avistamientos |
| `POST /api/protectoras/local/contactar` | RF-06 pide una «opción específica» para la protectora de la localidad, que no puede resolverse con un `{id}` en la ruta porque el usuario aún no lo conoce |
| `GET /api/admin/usuarios` | RF-11 dice «gestionar a todos los usuarios», lo que exige poder listarlos |
| `PATCH /api/admin/reportes/{id}/revisado` | El enum `EstadoReporte` tiene el valor `REVISADO`, que necesita una transición. **Implementado en el bloque 9 como `PUT /api/admin/reportes/{id}/resolver`** (§11, §18): fija por completo el estado, como el resto de transiciones de esta API, en vez de modificar un campo suelto |
| `GET /api/avistamientos/{id}` | Consultar el detalle de un avistamiento |
| `POST /api/chats/{id}/mensajes`, `PUT /api/chats/{id}/mensajes/leidos` | El campo `leido` de MENSAJE necesita una transición; el POST es la alternativa REST al WebSocket |
| Parámetro `?tipo=` en `DELETE /api/admin/publicaciones/{id}` | Las publicaciones son de tres tablas distintas: el id solo no identifica el recurso |
| `POST /api/fotos`, `GET /fotos/{nombre}` | **Añadidos en el bloque de subida de fotos (§21).** El resumen daba por hecho que `fotoUrl` llegaba ya alojada en alguna parte, sin decir dónde. Sin subida propia, la foto de perfil no se podía borrar en la baja (RF-19) y cualquier cadena acababa dentro de un `<img src>` |

### Cambios en el modelo de datos derivados de esta revisión

Dos requisitos no eran implementables con el modelo del SRS tal cual:

| Cambio | Por qué |
|---|---|
| `MATCH_REGISTRO.interesado` (boolean) + restricción única `(usuario_id, publicacion_id, tipo_publicacion)` | RF-15 no tenía dónde persistirse: el modelo solo registraba matches positivos, así que un descarte no podía excluirse del feed entre sesiones o dispositivos |
| Nuevo enum `TipoContenido {ALERTA, AVISTAMIENTO, ADOPCION}` y campo `REPORTE.tipoContenido` | El id de una publicación solo es único dentro de su tabla. Sin discriminador, ni el borrado del admin (RF-11) ni un reporte con `tipoObjetivo=PUBLICACION` (RF-26) identifican el recurso |

### Endpoints descartados en la revisión

| Endpoint | Por qué se descarta |
|---|---|
| `GET /api/usuarios/{id}` | Superficie de enumeración de toda la base de usuarios, incompatible con RNF-10. Los datos públicos del interlocutor van embebidos en las respuestas que los necesitan |
| `GET /api/avistamientos?alertaId=` | Redundante: `AlertaDetalleResponse` ya incluye los avistamientos de la alerta |
| `PATCH /api/notificaciones/leidas` | Solo comodidad de cliente, y `leidas` es un segmento literal en la posición donde su ruta hermana espera un id |

---

## 16. Bloque de mantenimiento del esquema

El esquema pasó a gestionarse con **migraciones Flyway** (`src/main/resources/db/migration`) y
`ddl-auto=validate`. Lo que hasta entonces mantenía `ddl-auto=update` solo sabía añadir tablas,
columnas e índices: cambiar el tipo de una columna, un `CHECK` o una unicidad lo ignoraba sin un solo
aviso, dejando el código y la base de datos describiendo cosas distintas.

### Lo que cambió en el contrato

| Cambio | Dónde |
|---|---|
| Las marcas de tiempo son instantes UTC con `Z`, y `fechaAvistamiento` debe enviarse con zona | §1 *Marcas de tiempo* |
| `DELETE /api/adopcion/{id}` deja el animal en `RETIRADO` en vez de borrar la ficha | §8 *Estados de un animal* |
| Nuevo `400 ANIMAL_RETIRADO` al confirmar la adopción de un animal retirado | §8 |
| `ANIMAL_NO_DISPONIBLE` cubre ahora `ADOPTADO` y `RETIRADO`, con mensajes distintos | §6 |
| El día de la cuota diaria se fija a `Europe/Madrid`: `reiniciaEn` ya no depende de la zona de la máquina | §6 *Estado del límite diario* |
| Tres tipos nuevos de notificación —`PROTECTORA_RECHAZADA`, `CUENTA_BLOQUEADA`, `CUENTA_DESBLOQUEADA`— para las decisiones del administrador (`V6`) | §11, §12 |
| `AVISTAMIENTO` tiene estado: `VISIBLE` \| `ELIMINADO`, con `NOT NULL` y su `CHECK` (`V7`) | §11 |

**Sobre `V6`.** Hibernate mapea un `@Enumerated(STRING)` a `varchar` con un `CHECK (col IN (...))`, y
`validate` **no** comprueba los `CHECK`: sin la migración la aplicación arrancaría igual y el `INSERT`
de una notificación del tipo nuevo fallaría en ejecución. La migración borra y recrea
`notificacion_tipo_check` con los nueve valores.

**Las pruebas no estaban corriendo con `validate`, y ahora sí.** `application-test.properties` importa
el `.env` para no versionar credenciales, y ese `.env` arrastraba un `JPA_DDL_AUTO=update` de la época
anterior a Flyway. Como `application.properties` declara
`spring.jpa.hibernate.ddl-auto=${JPA_DDL_AUTO:validate}`, el resultado era que la suite se ejecutaba
con **`update`** sin que nada lo dijera: Hibernate podía crear al vuelo la columna que a una migración
se le hubiera olvidado, que es exactamente lo que estas pruebas existen para detectar. El perfil de
test fija ahora `validate` de forma explícita. Conviene leer con esa reserva cualquier afirmación
anterior sobre migraciones «verificadas contra `validate`»: hasta este cambio, no lo estaban.

**Sobre `V7`.** La columna se añade en tres pasos —nulable, `UPDATE` de las filas existentes a
`'VISIBLE'`, y solo entonces `SET NOT NULL`— porque `AVISTAMIENTO` puede tener filas y un
`ADD COLUMN NOT NULL` sin defecto las rechazaría. **No** lleva `DEFAULT` en la base de datos a
propósito: el valor lo pone `AvistamientoService` al crear, y un defecto en la tabla escondería el día
que dejara de ponerlo.

### Dos cosas que se decidieron NO cambiar

Se estudiaron en el mismo bloque y se dejaron como están. Conviene que quede escrito para que no se
retomen por inercia ni se lean como descuidos:

**La referencia polimórfica de `MATCH_REGISTRO` se queda.** `publicacion_id` apunta a
`AVISTAMIENTO` o a `ANIMAL_ADOPCION` según `tipo_publicacion`, sin clave ajena que lo respalde. Las
dos alternativas —un arco exclusivo con dos columnas nulables y un `CHECK`, o un supertipo
`PUBLICACION` del que hereden ambas— resolverían la integridad, y la segunda es lo que se elegiría
empezando de cero. Ninguna se ha aplicado porque **ningún requisito la exige** y el riesgo no está
compensado: obliga a reescribir las dos entidades, sus repositorios y las consultas, con 512 pruebas
apoyadas en la forma actual. La debilidad está contenida: la referencia va siempre emparejada con el
tipo en los cuatro sitios donde se lee (`ChatRepository`, `CatalogoAdopcionSpec`,
`MatchService.resolverAutor` y las consultas del panel en `MatchRegistroRepository`), y el tercero es
un `switch` exhaustivo sin rama por defecto, así que un tipo nuevo rompe la compilación en lugar de
colarse en ejecución. Se reabre si aparece un tercer
tipo deslizable o un incidente real de integridad.

RF-11 es justamente el caso que muestra el filo de esta decisión: la retirada de avistamientos tuvo
que hacerse con un estado en vez de un `DELETE` precisamente porque no hay clave ajena que avisara del
destrozo (§11).

**No hay tabla `CHAT_PARTICIPANTE`, y es lo correcto.** Los dos extremos de un chat se deducen del
match, son siempre exactamente dos y no cambian nunca. Una tabla de participantes sabría representar
cero, uno o siete, y admitiría altas y bajas: expresaría estados que este dominio no admite, y
haría falta defenderla con restricciones adicionales para volver a prohibir lo que hoy es imposible
por construcción. Además duplicaría un dato que ya está en el match. Lo resuelve
`ChatService.exigirParticipante`, único punto de entrada al chat, cubierto por las pruebas del
bloque 5.

### Fuera de alcance, pendientes

| Pendiente | Bloque |
|---|---|
| `VALORACION`: unicidad `(usuario_id, protectora_id)` y `PROTECTORA.valoracion_media`, hoy derivada y sin mantener | 9, con `ValoracionService` |
| ~~Borrado / anonimización de usuario (RF-19)~~ | **Cerrado en el bloque 10**: la baja anonimiza la fila y conserva las nueve tablas que le apuntan (§19) |
| El día de la cuota está fijado a `Europe/Madrid` (§6): correcto para la península, no para Canarias. Contarla por zona del cliente exige que la envíe y guardarla junto al contador | RF-24 |
| `ANIMAL_ADOPCION` no lleva `@Version`: confirmar la adopción y retirar el animal a la vez dejan ganar a la última escritura. Los dos desenlaces son estados legales, así que no corrompe nada — mismo criterio que la comprobación de duplicados de RF-25 | Baja prioridad, sin bloque asignado |
| Índices funcionales de `ANIMAL_ADOPCION` sobre `lower(especie)` y `lower(zona_geografica)` | Cuando el catálogo tenga volumen que lo justifique (ver V4) |
| `MATCH_REGISTRO` no tiene índice por `(tipo_publicacion, interesado)`. Las consultas del panel de RF-10 filtran por ahí, y el único índice existente empieza por `usuario_id`, así que no las sirve. Con los volúmenes del proyecto no se nota | Cuando el panel tenga volumen que lo justifique |
| El `motivo` de un bloqueo o un rechazo no llega al usuario: solo queda en el log, porque `NOTIFICACION` no tiene campo de texto (§11). Exige una columna nueva o una tabla de auditoría de acciones del administrador | Sin bloque asignado; decisión de modelo pendiente |
| No hay endpoint de alta de administradores, ni debe haberlo: la cuenta `ADMIN` se crea desde la base de datos, como la cuenta raíz de cualquier panel | Fuera de alcance |

---

## 17. Bloque de refuerzo de la autenticación

Cierre de los riesgos que salieron de la auditoría de tokens y sesiones. No añade ningún requisito
funcional: endurece RF-13, RF-16 y RF-17 sobre lo que ya existía.

### Lo que cambió en el contrato

| Cambio | Dónde |
|---|---|
| Nuevo `429 DEMASIADOS_INTENTOS`, con cabecera `Retry-After`, en login, registro y recuperación | §2 |
| El coste de BCrypt sube de 10 a 12 | `SecurityConfig` |
| El JWT de acceso pierde los claims `uid` y `rol`: solo lleva `sub`, `jti`, `tipo`, `iat` y `exp` | `JwtService` |
| Las revocaciones de token pasan a la tabla `TOKEN_REVOCADO` (`V8`) y sobreviven al reinicio | `TokenRevocadoService` |
| Cada `SUBSCRIBE` de WebSocket revalida token, revocación y estado de la cuenta | §13 |

**Sobre los claims retirados.** No los leía nadie: `JwtAuthFilter` recarga la fila del usuario en
cada petición y construye las autoridades desde ella, precisamente para que un bloqueo o un cambio de
rol surtan efecto en el acto en vez de esperar a que caduque el token. Lo único que hacían era
publicar quién eres y qué puedes hacer a cualquiera que leyera el token —un JWT va **firmado, no
cifrado**, así que su contenido se lee en claro—, y el token aparece en sitios incómodos, como la
query del handshake WebSocket, que acaba en los registros del servidor (RNF-10).

**Sobre el coste 12 de BCrypt.** El coste viaja dentro del propio hash, así que subirlo **no**
invalida las contraseñas ya guardadas: siguen verificándose con el 10 con el que se crearon. Lo que
queda pendiente es rehashearlas al entrar (ver más abajo).

**Sobre `V8`.** La lista de tokens revocados vivía en un `ConcurrentHashMap` dentro del service, de
modo que cada despliegue la vaciaba y un token del que alguien había hecho *logout* volvía a
autenticar durante lo que le quedara de sus 24 horas. La tabla guarda **solo el `jti`** y su
caducidad, nunca el token entero, y se purga sola al revocar. `TokenRevocadoService` conserva su
interfaz —`revocar` y `estaRevocado`—, así que ningún otro punto del código cambió.

### La revalidación en cada `SUBSCRIBE`

El handshake comprueba el token **una sola vez**, al abrir la conexión. A partir de ahí la sesión
vivía por su cuenta: sobrevivía al logout, al bloqueo por un administrador y al cambio de contraseña,
y seguía admitiendo suscripciones nuevas durante horas. Chocaba de frente con el cuidado del lado
REST, donde cada petición recarga el usuario para que un bloqueo se note en el acto.

`ChatSuscripcionInterceptor` repite ahora, en cada suscripción y antes de decidir nada sobre el
destino, las mismas cuatro comprobaciones de `JwtAuthFilter` y en el mismo orden: el token no ha
caducado, no está revocado, la cuenta sigue activa y el token no es anterior al último cambio de
contraseña. La sesión guarda el `jti`, la caducidad y la emisión —**no el token**, que sería una
credencial utilizable más de la que preocuparse—, y si esos datos faltan la suscripción se rechaza en
vez de dejarla pasar por no poder comprobarla.

### Limitaciones aceptadas, con su razón

Se estudiaron en este bloque y se dejaron como están. Queda escrito para que no se lean como
descuidos ni se retomen por inercia.

**La expiración sigue en 24 h y no hay tokens de refresco.** Es la decisión de alcance más grande del
bloque, y es consciente. Ningún RF exige sesiones cortas ni revocación por dispositivo, y el patrón
—JWT puro con caducidad larga y una lista de revocados— es conocido y aceptable para un proyecto de
este tamaño. Los tokens de refresco no son una mejora gratuita: traen una tabla de refresco, rotación
con detección de reutilización, un endpoint nuevo y su propio manejo en el cliente, y cada una de esas
piezas es una superficie que se puede implementar mal. Lo que de verdad reduce la ventana de un token
robado ya está puesto: el logout revoca de verdad —y ahora sobrevive al reinicio—, un cambio de
contraseña invalida de golpe todo lo emitido antes, y un bloqueo se nota en la siguiente petición.
Se reabre si aparece un requisito de sesión por dispositivo o de cierre remoto.

**Una conexión WebSocket ya abierta no se cierra al bloquear ni al cerrar sesión.** La revalidación
impide suscribirse y **escribir**, pero no cancela las suscripciones ya hechas: un usuario bloqueado
mientras tenía un chat abierto sigue **recibiendo** lo que se publique en él hasta que su cliente se
desconecte. Cerrarlas exige un registro de sesiones WebSocket vivas por usuario —escuchar
`SessionConnectedEvent` y `SessionDisconnectEvent`, guardar el `sessionId` y cerrarlo desde
`AdminService`—, que es una pieza nueva con su propio estado que mantener. Lo que queda abierto es
recibir, que es pasivo.

> **Corrección.** Este párrafo decía antes que lo único cerrado eran las suscripciones nuevas, y era
> verdad: `ChatSuscripcionInterceptor` solo miraba los `SUBSCRIBE`, así que una cuenta bloqueada o
> dada de baja podía **seguir escribiendo mensajes nuevos** por una conexión abierta. El envío va a
> `/app/chat/{chatId}/enviar`, que no pasa por `JwtAuthFilter` —no es HTTP—, y `MensajeService` solo
> comprueba la pertenencia al chat, no si la cuenta sigue activa. Se cerró en el bloque 10
> revalidando también los `SEND`. Escribir es peor que leer: deja datos nuevos en nombre de quien ya
> no debería poder actuar.

**`POST /api/auth/restablecer-password` no lleva límite de intentos.** Su credencial no es una
contraseña que se pueda adivinar a mano, sino un JWT firmado: lo que habría que acertar es una firma
HMAC-SHA256 de 256 bits, y eso no se hace a base de peticiones. Además, limitarlo dejaría sin salida
justo a quien está intentando recuperar una cuenta comprometida. **La contrapartida hay que decirla:**
esa garantía no es independiente, es exactamente la fuerza de `JWT_SECRET`. Con un secreto adivinable
el ataque ni siquiera sería online —basta registrarse, quedarse un token propio y romper el secreto
fuera del servidor— y quien lo lograra podría fabricarse un enlace de recuperación para cualquier
cuenta. Por eso el secreto **debe** generarse al azar (`openssl rand -base64 48`) y nunca elegirse a
mano.

**El límite por cuenta es un arma de doble filo, y se asume.** Un tercero puede dispararlo contra la
cuenta de otro y dejarlo sin entrar hasta quince minutos. Es el precio conocido de cualquier bloqueo
por cuenta. Se mitiga con que un intento ya bloqueado **no** se apunta, de modo que la ventana no se
renueva por insistir: el castigo tiene techo y no depende de cuánto aporree el atacante.

**Los contadores viven en memoria.** Un reinicio los borra —una ventana de quince minutos de
tolerancia, no una sesión resucitada— y no se comparten entre instancias, así que con la API
replicada el límite efectivo se multiplicaría por el número de réplicas. Persistirlos en base de datos
haría escribir en cada intento fallido, que es justo lo que un ataque provoca en masa; lo razonable
llegado el caso sería Redis, y eso es una dependencia de infraestructura que este proyecto no tiene.

**La dirección se toma de `getRemoteAddr()`, no de `X-Forwarded-For`.** Esa cabecera la pone el
cliente y se falsifica sola: fiarse de ella convertiría el límite por IP en un adorno, porque bastaría
con mandar una distinta en cada intento. Si esto se despliega detrás de un proxy inverso habrá que
leerla, pero **solo** tras configurar la lista de proxies de confianza.

### Fuera de alcance, pendientes

| Pendiente | Bloque |
|---|---|
| Rehash al entrar: las contraseñas guardadas siguen con el coste 10 con el que se crearon. Subirlas al 12 exige recodificarlas en el login, cuando la contraseña en claro está disponible y ya se ha comprobado | Sin bloque asignado |
| Purga periódica de `TOKEN_REVOCADO`: hoy se limpia al revocar, así que una instalación sin logouts acumularía filas caducadas indefinidamente. Una tarea `@Scheduled` lo resolvería | Sin bloque asignado |
| Cierre activo de sesiones WebSocket al bloquear una cuenta (ver arriba) | Sin bloque asignado |

---

## 18. Bloque de valoraciones y reportes (RF-23, RF-26)

Cierra los dos requisitos que quedaban del núcleo social: puntuar a una protectora con la que se ha
tratado, y avisar de contenido que incumple las normas.

### Lo que cambió en el contrato

| Cambio | Dónde |
|---|---|
| Valorar pasa de `POST`/`201`/`409` a **`PUT` idempotente** con `200` | §9 |
| Nuevo `403 SIN_INTERACCION_PREVIA`: no se valora sin contacto previo | §9 |
| `PROTECTORA.valoracion_media` **desaparece**; la media se calcula al leer (`V9`) | §9, §16 |
| Nuevo índice `idx_valoracion_protectora` para esa lectura (`V10`) | §18 |
| `GET /api/adopcion/{id}` gana `protectoraValoracionTotal` y omite la media cuando no la hay | §8 |
| `POST /api/reportes` implementado; se retira el `409` por duplicado | §10 |
| Nuevo `PUT /api/admin/reportes/{id}/resolver`, noveno endpoint del panel | §11 |

### Sobre `V9`

Dos cambios que son la misma decisión: la valoración de un usuario sobre una protectora es un dato
único, y la media es una consulta, no un dato.

`UNIQUE (usuario_id, protectora_id)` no sustituye a la comprobación del service, la respalda: sin
ella, dos peticiones simultáneas del mismo usuario leerían las dos "no existe" antes de que ninguna
insertara, y la media quedaría inflada por alguien que ha votado dos veces. Ninguna prueba de un solo
hilo lo detectaría. Es el criterio del resto del esquema: lo que no puede pasar, que no lo permita la
tabla. `validate` **no** comprueba unicidad, así que la restricción tenía que crearse en la migración
además de declararse en la entidad.

El `DELETE` previo de duplicados es una red, no una necesidad: la tabla estaba necesariamente vacía
porque hasta este bloque no había forma de escribir en ella. Pero una migración que falla al aplicarse
es una manera pésima de descubrir lo contrario.

### La resolución polimórfica no se ha duplicado

Reportar una publicación exige saber si existe, y "publicación" son tres tablas —`ALERTA`,
`AVISTAMIENTO`, `ANIMAL_ADOPCION`— con ids que solo son únicos dentro de cada una. Ese reparto ya lo
hacía `AdminService.retirarPublicacion`, así que la pregunta era dónde ponerlo sin escribirlo dos
veces.

La respuesta es `PublicacionService`, con un `existe(TipoContenido, id)` cuyo **switch es una
expresión sin rama por defecto**: el día que aparezca un cuarto tipo de publicación, deja de compilar
y señala el sitio. No absorbe la retirada del administrador, y no por descuido: aquella decide además
qué estado terminal aplicar a cada tabla y qué notificar. Son dos preguntas distintas sobre el mismo
reparto —aquí "¿existe?", allí "retírala"—; lo que se comparte es la forma, no la lógica.

Es la lección que dejó el bloque 8, donde un `switch` que parecía exhaustivo no lo era por ser una
sentencia en lugar de una expresión.

### Qué se enseña de un reporte, y qué no

Del autor, solo `AutorResponse`: id, nombre y foto, sin email ni teléfono (RNF-10). Del objetivo, solo
el par `(tipoObjetivo, objetivoId)`. **Nunca los datos de la persona reportada**, ni en la respuesta
del `POST` ni en la cola del administrador.

No es una omisión conservadora sino el punto del diseño: un reporte es una acusación sin revisar, y un
endpoint que devolviera el contacto del acusado sería una forma cómoda de sacarle el teléfono a
alguien con quien se ha discutido. Por eso tampoco existe un `GET /api/reportes/mios`: leer reportes
es trabajo de moderación, y quien denuncia no gana por ello ningún acceso a la otra parte.

### Limitaciones aceptadas

**`POST /api/reportes` es un oráculo de existencia de usuarios.** Responde `404` si el id no existe y
`201` si el reporte se crea, así que un autenticado puede averiguar qué ids de usuario existen
probándolos uno a uno. Se asume por tres motivos: lo que se aprende es solo *"el id N existe"*, sin
nombre, email ni teléfono; cada sondeo positivo **deja un reporte en la cola del administrador**, de
modo que es un canal ruidoso y rastreable, no discreto; y la alternativa —aceptar en silencio reportes
contra objetivos inexistentes— empeora justo el problema que la comprobación resuelve. Para calibrarlo:
`POST /api/auth/registro` ya revela con un `409` si un **email** está registrado, que es un oráculo
bastante más útil para quien quiera enumerar cuentas.

**El índice de `V9` no sirve para leer por protectora, y por eso hay un `V10`.** `UNIQUE (usuario_id,
protectora_id)` es un B-tree compuesto que solo se recorre por un prefijo de sus columnas: vale para
"las valoraciones de este usuario", no para "las de esta protectora", que es lo que consultan la media
y el recuento. Al mover la media de una columna guardada a una agregación en lectura, cada
`GET /api/adopcion/{id}` habría provocado un recorrido secuencial de `VALORACION`. Ninguna prueba lo
detecta: la suite corre con un puñado de filas, donde el recorrido secuencial es más rápido.

### Fuera de alcance, pendientes

| Pendiente | Bloque |
|---|---|
| ~~RF-06 sigue sin implementar~~ | **Cerrado en el bloque del directorio de protectoras (§20)**, salvo los dos endpoints de contacto, que siguen abiertos allí |
| Reportes duplicados: hoy un usuario puede reportar el mismo objetivo tantas veces como quiera. Impedirlo exige `UNIQUE (usuario_id, tipo_objetivo, objetivo_id)` y decidir qué hacer con un segundo reporte por un motivo distinto, que puede ser información legítima y no ruido | Sin bloque asignado |
| Índice sobre `REPORTE (estado, created_at)`: la cola del administrador filtra y ordena por ahí. Con los volúmenes del proyecto no se nota | Cuando la cola tenga volumen que lo justifique |
| `GET /api/admin/reportes` no pagina. Sigue el patrón de la cola de protectoras pendientes —una bandeja que se consume entera—, pero esta puede crecer sin límite, y a diferencia del listado de usuarios no tiene tope de página | Cuando la cola tenga volumen que lo justifique |
| `mediaDeProtectora` y `countByProtectoraId` son dos consultas donde cabría una: `SELECT AVG(...), COUNT(...)` ahorraría un viaje a la base de datos por cada detalle de animal | Optimización menor, sin bloque asignado |
| Una protectora puede ser valorada por quien contactó con ella y nunca llegó a hablar: el criterio es el deslizamiento a favor, no el mensaje enviado. Afinarlo exigiría mirar `MENSAJE`, y dejaría fuera al que contactó y no recibió respuesta, que es justo la queja más común | Decisión tomada, se reabre si aparece abuso |

---

## 19. Bloque de gestión de cuenta (RF-02, RF-18, RF-19)

Perfil propio, idioma y baja de cuenta. Cierra el pendiente que el bloque de mantenimiento dejó
abierto: *«Borrado / anonimización de usuario (RF-19): nueve claves ajenas apuntan a `USUARIO` y
ninguna dice qué hacer al borrarlo»*.

### La decisión: la baja anonimiza, no borra

Un `DELETE` en cascada sobre `USUARIO` habría arrastrado las **nueve** tablas que le apuntan:
`PROTECTORA`, `ALERTA`, `AVISTAMIENTO`, `MATCH_REGISTRO`, `MENSAJE`, `VALORACION`, `REPORTE`,
`NOTIFICACION` y `LIMITE_MATCH_DIARIO`. Y con ellas se habría llevado por delante **la mitad de
conversaciones ajenas**, porque un chat tiene dos participantes: al borrar a uno, el otro pierde el
hilo entero de algo que quizá siga en marcha —una mascota que aún no ha aparecido, una adopción a
medias— y ni siquiera puede releer lo ya hablado.

Quien se da de baja tiene derecho a que sus datos personales desaparezcan. No a borrar la memoria de
las conversaciones de los demás. Así que las filas se quedan y lo que desaparece es la persona.

| Campo | Queda |
|---|---|
| `nombre` | `"Usuario eliminado"`, el mismo para todos |
| `email` | Un hash suyo en un dominio inexistente (ver abajo) |
| `password_hash` | Un valor sin formato BCrypt: `matches()` devuelve `false` sin comparar nada |
| `telefono`, `foto_url` | `null` |
| `activo` | `false` |
| `rol`, `created_at`, `id` | **Intactos**: no son datos personales y explican el histórico |

**La foto de perfil, además, se borra de verdad** (desde el bloque de subida de fotos, §1). Vaciar
`foto_url` no bastaba: la imagen seguía publicada en su URL. Ahora su fila de `FOTO` se borra en la
misma transacción que la anonimización —y en ese instante `GET /fotos/{nombre}` deja de servirla— y
su archivo justo después del commit. Si borrar el archivo fallara, queda en disco sin fila y sin
servirse hasta que lo recoge la limpieza diaria. **Las fotos de sus alertas y avistamientos no se
tocan**: son fotos de animales y forman parte del histórico que la otra parte necesita ver; por lo
mismo, si la foto de perfil se usaba también en una publicación, se conserva. Las fotos con URL
externa anteriores a `/api/fotos` quedan fuera de este borrado (limitación aceptada, §20).

El nombre genérico es el mismo para todos a propósito. Uno con el id dentro —«Usuario 482»— seguiría
distinguiendo a una persona de otra a lo largo de la aplicación, que es justo lo que se deshace.

### La baja no necesita cerrar sesiones: ya estaba resuelto

Poner `activo = false` basta, y no por casualidad. `JwtAuthFilter` recarga la fila del usuario en
**cada** petición precisamente para que un bloqueo administrativo surta efecto en el acto (§11), y
`ChatSuscripcionInterceptor` hace lo mismo en cada `SUBSCRIBE` desde el bloque de refuerzo (§17). La
baja se apoya en esa maquinaria en lugar de traer la suya: el token que el usuario tenga en la mano
deja de valer en la siguiente petición, sin revocar nada. Hay una prueba de extremo a extremo que lo
fija.

### El email no queda libre, y esa fue la parte difícil

Dos exigencias tiran en direcciones opuestas. El email es dato personal y tiene que desaparecer; pero
si desaparece sin más, la dirección queda libre y **cualquiera podría registrarse con el email de
quien se fue**. Ante la otra parte de cada conversación abierta, esa persona nueva aparecería como la
misma con la que estuvo hablando. El histórico se conserva justo para que esas conversaciones sigan
teniendo sentido, y eso lo volvería del revés.

Se resuelve sustituyendo el email por **un hash suyo**:

```
sha256(email normalizado) + "@cuenta-eliminada.invalid"
```

No se puede volver del hash al email —así que el dato original desaparece—, pero sí calcularlo para un
email candidato, que es todo lo que `AuthService` necesita para rechazar el registro. Es determinista,
así que ocupa siempre el mismo sitio, y usa el índice único que ya existía: ni columna nueva ni
migración. El dominio `.invalid` lo reserva el RFC 2606 para que nunca exista, de modo que ningún
registrador puede venderlo y ningún correo enviado ahí llega a ninguna parte.

El mensaje del `409` es idéntico al de un email ya registrado. Distinguirlos convertiría el registro
en una forma de averiguar quién se ha ido de la plataforma.

### Si quien se da de baja es una protectora

Sus animales **`DISPONIBLE` pasan a `RETIRADO`**. Un animal en adopción es una invitación a contactar
con quien lo publicó, y quien lo publicó acaba de irse: nadie va a responder, nadie puede confirmar la
adopción y nadie puede corregir la ficha. Dejarlos ofreciéndose haría perder el tiempo a cada
ciudadano que deslizara a favor. Se reutiliza el estado que el catálogo ya tiene para «esto ya no se
ofrece», el mismo al que llegan la retirada manual y la administrativa.

**Los `ADOPTADO` no se tocan.** Es un desenlace, no una oferta abierta: pasarlos a `RETIRADO` borraría
el hecho de que ese animal encontró casa, que es el dato que da sentido al histórico que esta baja se
esfuerza en conservar.

**La fila de `PROTECTORA` se anonimiza solo si detrás había una persona**, y lo decide el campo
`esPersonaFisica` que la propia protectora declara al registrarse (`V12`).

| `esPersonaFisica` | Al darse de baja |
|---|---|
| `false` — una asociación registrada | `nombreOrganizacion`, `direccion` y `zonaGeografica` **se conservan** |
| `true` — un particular que rescata por su cuenta | `nombreOrganizacion` → `"Protectora eliminada"`, `direccion` → `null` |

Los dos casos existen y no se distinguen mirando el dato. Para una asociación, el nombre y la
dirección son de la entidad y son los que explican de dónde salió cada animal publicado; borrarlos
dejaría cada adopción histórica sin procedencia. Pero en España es muy común que una protectora sea
alguien que rescata por su cuenta, sin estructura legal, que pone **su propio nombre** como
organización y **su domicilio particular** como dirección: para esa persona esas dos columnas son
datos personales tan identificables como su teléfono, y conservarlas sería dejar expuesta a quien
acaba de pedir que la borren.

**Se declara, no se deduce.** Nada en una cadena de texto distingue con fiabilidad «Huellas de
Getafe» de «María Gómez Ruiz», y equivocarse cuesta en las dos direcciones. Es un campo opcional en el
registro y **ausente equivale a `false`**, que es el caso mayoritario y el que conserva el histórico.

**La zona geográfica no se toca nunca**: es una comarca o una ciudad, no identifica a nadie, y es lo
que permite que el histórico siga diciendo dónde operaba quien publicó cada animal.

### Idioma (RF-18)

Cinco: `es`, `en`, `ca`, `gl`, `eu` —las lenguas oficiales de España más el inglés—. Hasta este bloque
la columna admitía **cualquier cadena de hasta diez caracteres**: «klingon» era un idioma
perfectamente válido para la base de datos, y no se habría notado hasta ir a traducir la interfaz.

Lo hacen cumplir tres capas, cada una porque falla en un momento distinto: el enum `Idioma` es la
única lista y da el `400` con los códigos admitidos; el registro cae al idioma por defecto en vez de
rechazar el alta, porque equivocarse en un campo opcional no es motivo para impedir que alguien se dé
de alta; y el `CHECK` de la migración `V11` es lo último que queda en pie si alguien escribe por SQL.

`Idioma` **no es el tipo de la columna**, a diferencia del resto de enums del modelo. El valor que
viaja es un código ISO 639-1, que se escribe en minúscula, y un `@Enumerated(STRING)` escribiría `ES`
y `CA`: habría que elegir entre romper el contrato o añadir un convertidor para deshacer lo que el
enum acaba de hacer.

### Limitaciones aceptadas

**La anonimización del email es un seudónimo, no anonimato perfecto.** Quien tenga acceso a la base de
datos y **ya sospeche** el email de una persona puede calcular el hash y confirmar si tuvo cuenta. Es
la contrapartida inevitable de poder reconocer el email al registrarse: sin esa capacidad, la
dirección quedaría libre. Un hash con pimienta lo cerraría, pero entonces perder esa pimienta
liberaría en silencio todos los emails borrados —un fallo peor, porque no se nota—. Se asume.

**El nombre puede seguir dentro del texto de los mensajes.** Se anonimiza la fila del usuario, no el
contenido que escribió: si alguien firmó un mensaje con su nombre, ahí sigue. Rastrearlo exigiría
analizar texto libre, con los falsos positivos que eso trae, y borraría contenido de una conversación
que pertenece también a la otra parte.

**Las protectoras registradas antes de `V12` quedan en `esPersonaFisica = false`, y no hay forma
retroactiva de saber cuáles eran personas.** Una protectora anterior a esa migración que sí lo fuera
seguirá sin la protección hasta que alguien lo declare a mano, porque el dato solo lo sabe ella y no
se puede inferir de su nombre. No se eligió `true` como valor por defecto porque sería peor:
anonimizaría el nombre de asociaciones reales al darse de baja, dejando sin procedencia el histórico
de adopciones que este diseño se esfuerza en conservar. Entre dejar un dato de más y borrar uno que
hacía falta, se deja el de más y se dice en voz alta. Cerrarlo requiere o bien preguntárselo a las
protectoras existentes, o bien un endpoint para que rectifiquen su ficha, que RF-10 no contempla.

**Nadie puede registrarse en `@cuenta-eliminada.invalid`**, y la prohibición no es celo de más. Los
emails anonimizados son deterministas, así que sin ella quien conociera el email de otra persona podía
calcular su hash, registrarse con esa dirección y dejar una mina: el día que la víctima quisiera darse
de baja, el `UPDATE` de su email chocaría contra el índice único y la baja fallaría con un `409` que
no explica nada. Se le habría impedido ejercer RF-19 de forma indefinida. Es un dominio que el
RFC 2606 reserva para que no exista, así que ninguna dirección legítima puede estar ahí.

### Fuera de alcance, pendientes

| Pendiente | Bloque |
|---|---|
| Cambio de email con verificación de la dirección nueva: correo de confirmación, token y dirección pendiente. RF-02 no lo pide | Sin bloque asignado |
| Exportación de los datos propios (derecho de portabilidad del RGPD, distinto del de supresión que cubre RF-19) | Sin bloque asignado |
| La baja no avisa a quien tuviera una conversación abierta. Se entera al ver «Usuario eliminado» en el chat | Decisión tomada, se reabre si molesta en uso real |

---

## 20. Bloque del directorio de protectoras (RF-06)

Listado público de protectoras y ficha de cada una, con sus animales y sus valoraciones.
`ProtectoraController` y `ProtectoraService` dejan de ser esqueletos.

### Lo que cambió en el contrato

| Cambio | Antes | Ahora |
|---|---|---|
| `GET /api/protectoras` | Diseñado, sin construir. Devolvía `ProtectoraResponse` | Construido. Devuelve `ProtectoraDirectorioResponse`: nombre, zona, `esPersonaFisica`, media, total y `contacto` |
| `GET /api/protectoras/{id}` | Diseñado, sin construir | Construido. `ProtectoraDetalleResponse`, con los animales `DISPONIBLE` paginados dentro y las diez valoraciones más recientes |
| `GET /api/protectoras/{id}/animales` | Diseñado | **Retirado del diseño.** Sus animales van dentro de la ficha, paginados por los `page` y `size` de esta |
| `ProtectoraResponse.valoracionMedia` | Se conservaba a la espera de RF-06 | Campo muerto: nadie lo rellena y el directorio no lo usa. Candidato a desaparecer (abajo) |
| `V13` | — | `idx_protectora_directorio (estado, lower(nombre_organizacion))` |

Las reglas de visibilidad, el criterio sobre el contacto y el orden alfabético están razonados en §9,
que es donde vive el contrato. Aquí solo lo que hizo falta decidir para construirlo.

### La decisión difícil: qué contacto se publica de una persona física

RF-06 pide el contacto de la protectora y este proyecto lleva desde el bloque 7 sin exponer el email
ni el teléfono de nadie. La contradicción es aparente —una protectora se registra para ser
contactada, un ciudadano no— y está resuelta en §9. Lo que **no** era evidente es el caso que abrió
`V12`: la protectora que es una persona particular rescatando por su cuenta.

Se consideraron tres salidas y se eligió la tercera:

1. **Publicarlo todo igual.** Coherente y simple, pero convierte el directorio en una lista de móviles
   personales que cualquiera con una cuenta puede recorrer entera. Descartada.
2. **No publicar nada de una persona física.** Deja a toda una clase de protectoras sin forma de ser
   contactadas, que es exactamente lo contrario de lo que RF-06 pide. Descartada.
3. **Email sí, teléfono y dirección no.** El email es asíncrono, filtrable y es el identificador sobre
   el que ya está construida la cuenta; el teléfono es un canal directo e inmediato y la dirección es
   un domicilio. Además el teléfono es opcional en el modelo, así que pudo rellenarse para que el
   administrador verificara la solicitud (RF-11) y no para un listado público.

El argumento de fondo es que **el coste de equivocarse no es simétrico**: ocultar un teléfono molesta,
enseñarlo expone un número personal de forma irreversible.

Sobre la dirección hubo una duda que conviene dejar escrita, porque la respuesta fácil era la
contraria. `GET /api/adopcion/{id}` **ya publica** `protectoraDireccion` sin mirar `esPersonaFisica`,
así que ocultarla aquí no protege a ninguna protectora que tenga un animal publicado, y podría
argumentarse que esconderla en un sitio y no en otro es peor que no esconderla. Se ha ocultado
igualmente: repetir una fuga porque ya existe la convierte en política, y la incoherencia queda
anotada abajo con su arreglo, que es una línea.

### Lo que se reutilizó, y por qué importa que se reutilizara

Nada de esto se ha vuelto a escribir:

- **La media y el recuento** los da `ValoracionService`, el mismo que sirve a `GET /api/adopcion/{id}`
  y al panel. No es solo ahorrarse un `AVG`: RF-06 y RF-07 publican la reputación de la misma
  protectora, y calcularla dos veces es la forma de que un día no coincidan. Hay una prueba de
  integración que pide los dos endpoints seguidos y compara los dos números contra PostgreSQL.
- **Los animales** los da `AnimalAdopcionService`, que ya tenía la forma de la consulta, el tope de
  página, el orden y la conversión a DTO del catálogo. La ficha es ese mismo catálogo con una tercera
  política de visibilidad: el público lista lo `DISPONIBLE` de todas, el panel lista todo lo de una, y
  este lista lo `DISPONIBLE` de una. El método es de paquete, no público, para que ningún controller
  pueda llamarlo saltándose la comprobación de visibilidad: no compilaría.
- **El listado de valoraciones** es la consulta que ya usaba el panel, ahora con un `Pageable`. Una
  sola consulta y dos políticas: el panel pasa `unpaged()` porque quiere su histórico entero, la ficha
  pasa un tope porque se sirve en cada visita.

`ValoracionService.resumenDeVarias` es la única pieza nueva de lectura, y existe para que el listado
no sea un N+1: una agregación agrupada para toda la página, no dos consultas por fila. Se llama
`resumenDeVarias` y no es una sobrecarga de `resumenDe` a propósito —con el mismo nombre, un
`resumenDe(any())` en cualquier prueba deja de compilar por ambiguo y rompe pruebas que no tienen nada
que ver—.

### Dos cosas que se descubrieron construyendo esto

**Una protectora dada de baja seguía siendo `ACTIVA`.** La baja de RF-19 anonimiza al usuario y retira
sus animales, pero no toca la fila de `PROTECTORA`; un bloqueo del administrador tampoco. Filtrando
solo por estado, el directorio habría publicado la ficha de una protectora que ya no existe y habría
ofrecido como forma de contactarla su email anonimizado `@cuenta-eliminada.invalid`. El listado y la
ficha comprueban además `USUARIO.activo`, y hay dos pruebas de integración que dan de baja y bloquean
de verdad antes de volver a pedir el listado.

No se ha tocado RF-19 para que la baja ponga la protectora en otro estado: `EstadoProtectora` describe
el proceso de verificación del administrador (RF-11) y meterle un valor con otro significado mezclaría
dos cosas que hoy están separadas. La condición sobre la cuenta es más barata y dice exactamente lo
que pasa.

**Y tirando de ese hilo apareció un agujero anterior, que se ha cerrado aquí.** Sacar a una protectora
del directorio no la sacaba del **catálogo de adopción**: `PUT /api/admin/protectoras/{id}/rechazar`
solo cambiaba `estado` y `PUT /api/admin/usuarios/{id}/bloquear` solo ponía `activo = false`, y
`GET /api/adopcion`, `GET /api/adopcion/{id}` y el feed de swipe no miran ni el estado de la protectora
ni si su cuenta sigue viva. Comprobado ejecutándolo contra el servidor real antes de arreglarlo: tras
rechazar a una protectora que estaba `ACTIVA`, su animal seguía `DISPONIBLE` en el escaparate, y un
ciudadano podía deslizar a favor y abrir un chat con una cuenta que ya no puede entrar.

Era el argumento que la baja voluntaria ya escribió —*«un animal en adopción es una invitación a
contactar con quien lo publicó, y quien lo publicó acaba de irse»*— aplicado en RF-19 y no en RF-11.
La causa de raíz es que ese código vivía como **método privado de `UsuarioService`**, así que solo
podía usarlo la vía que lo tenía a mano. Ver **«Dos acciones administrativas amplían su efecto»**, más
abajo.

**El filtro de zona no podía escribirse como en el resto de la API.** El patrón habitual
`:zona IS NULL OR lower(...)` es justamente el que ya reventó una vez contra PostgreSQL: un parámetro
nulo no tiene tipo, se toma por `bytea` y `lower()` falla con *«function lower(bytea) does not
exist»*. Aquí el servicio convierte el filtro en un patrón `LIKE` que **nunca es nulo** —`%` cuando no
se filtra— y la consulta es una sola, sin ramas. El `coalesce` sobre `zona_geografica` es para que una
protectora sin zona declarada no se caiga del listado sin filtro, porque `NULL LIKE '%'` es `NULL` y
no cierto.

### Dos acciones administrativas amplían su efecto (cambio de comportamiento)

Esto **no es una corrección interna**: dos endpoints de RF-11 hacen ahora algo que antes no hacían, y
un cliente lo nota.

| Endpoint | Antes | Ahora |
|---|---|---|
| `PUT /api/admin/protectoras/{id}/rechazar` | Solo cambiaba `estado` a `RECHAZADA` | Además, **si estaba `ACTIVA`**, retira a `RETIRADO` todos sus animales `DISPONIBLE` |
| `PUT /api/admin/usuarios/{id}/bloquear` | Solo ponía `activo = false` | Además, **si la cuenta tiene rol `PROTECTORA`**, retira a `RETIRADO` todos sus animales `DISPONIBLE` |

Ni el código de respuesta ni el cuerpo cambian; lo que cambia es el estado que dejan detrás. Un panel
que liste el catálogo después de rechazar verá menos animales que antes, y eso es lo que se busca.

**Rechazar a una `PENDIENTE` no retira nada**, y por eso las dos ramas del `switch` están separadas:
una protectora que nunca llegó a verificarse no ha podido publicar —hacerlo exige un token y una
`PENDIENTE` no puede ni entrar—, así que la consulta sobraría.

**Bloquear a un ciudadano tampoco toca ningún catálogo.** Sus alertas y avistamientos no son ofertas
que alguien tenga que atender, y retirarlas sería otra decisión, que RF-11 no pide.

**No se deshace al desbloquear ni al volver a aprobar**, y es deliberado: `RETIRADO` es terminal en
todo el proyecto —tampoco se vuelve de él a mano, ni tras una adopción fallida— y devolver los animales
al escaparate automáticamente reabriría fichas cuyos matches son de otro ciclo. La protectora
rehabilitada vuelve a publicar lo que siga ofreciendo, que es una decisión suya y no del sistema.

El código se ha movido a `AnimalAdopcionService.retirarTodosLosDisponibles`, de paquete, que es quien
gobierna los estados del animal; la baja de RF-19 pasa ahora por ahí también. Una sola implementación
es lo que impide que la próxima vía que deje sin dueño a una protectora se olvide de esto.

### La dirección de un particular deja de publicarse también en el catálogo

`GET /api/adopcion/{id}` devolvía `protectoraDireccion` sin mirar `esPersonaFisica`. Mientras eso
siguiera así, que el directorio la ocultara era un gesto: bastaba con abrir la ficha de cualquiera de
sus animales. Ahora los dos endpoints preguntan al **mismo** método,
`ProtectoraService.direccionPublicaDe`, así que no pueden responder distinto. El campo se omite del
JSON, no viaja como `null`.

Es un cambio de contrato pequeño y consciente: un cliente que esperara `protectoraDireccion` siempre
presente ahora tiene que tolerar su ausencia, igual que ya la tolera en `valoracionMedia`.

### `ProtectoraResponse.valoracionMedia` se ha retirado

Nadie lo rellenaba. Existió como copia de `PROTECTORA.valoracion_media` —columna eliminada en `V9`— y
después se conservó a la espera de este directorio, que acabó usando DTOs propios. **El JSON no
cambia**: era `@JsonInclude(NON_NULL)` y valía siempre `null`, así que no llegaba a serializarse nunca.
Un campo que siempre vale `null` es una promesa que el DTO no cumple.

### Sobre `V13`

`idx_protectora_directorio (estado, lower(nombre_organizacion))` cubre el filtro y el `ORDER BY` a la
vez, que es para lo que sirve un btree compuesto. El `lower()` no es cosmético: un índice solo sirve
para un `ORDER BY` si ordena por lo mismo que este pide, y sin él el orden dependería de la
intercalación con la que se creó la base de datos —bajo la *C locale*, «Zamora» va antes que
«abrigo»—. Es el mismo recurso que `uk_usuario_email_lower` (`V5`) e `idx_alerta_especie_lower`
(`V4`).

**No arregla ninguna lentitud medida**, a diferencia de `V10`, y conviene decirlo: con las diez filas
que hay hoy el planificador seq-scanea y lo ignora, como se comprobó con un `EXPLAIN`. Se crea porque
es el índice que la forma de la consulta pide y porque `PROTECTORA` se escribe muy de vez en cuando
—un alta, una verificación—, así que su coste de mantenimiento es prácticamente nulo. No acompaña a
ningún cambio de entidad: RF-06 solo lee. Se versiona igualmente porque `ddl-auto=validate` no
comprueba índices.

### Limitaciones aceptadas

| Limitación | Por qué se acepta |
|---|---|
| **Las protectoras anteriores a `V12` quedaron en `esPersonaFisica = false`**, así que si alguna era un particular, su teléfono y su dirección se publican. Es la limitación conocida de `V12` con un alcance nuevo: antes solo afectaba a lo que la baja borraba, ahora también a lo que se enseña | No hay forma retroactiva de saberlo, y nadie puede declararlo todavía: ni `PUT /api/usuarios/me` ni el panel tocan ese campo. Se arregla dejando que la protectora lo edite, abajo |
| El listado no ordena por valoración ni por número de animales | RF-06 no lo pide y ordenar por nota exigiría meter la agregación en la consulta principal. El orden alfabético es el de un directorio |
| El filtro `zona` no escapa los comodines de `LIKE` (`%`, `_`) | `GET /api/adopcion` tiene el mismo parámetro, con el mismo nombre, y tampoco los escapa: escaparlos aquí haría que dos filtros que el cliente ve iguales se comportaran distinto. No abre nada, porque un `%` devuelve lo que ya devuelve omitir el filtro |
| La ficha enseña diez valoraciones y no admite paginarlas | El número real viaja en `totalValoraciones`. Quien quiere el histórico entero es la propia protectora, y lo tiene en `/panel/valoraciones` |
| El directorio exige token | «Público» aquí significa «cualquier autenticado». Abrirlo sin token lo convertiría en una fuente de direcciones de correo que cualquiera puede recorrer sin registrarse |
| **Las fotos anteriores al bloque de subida de fotos quedan fuera del borrado de RF-19.** Toda `fotoUrl` guardada antes de `POST /api/fotos` (§1) es una URL externa que puso el cliente: al anonimizar la cuenta se vacía la columna, pero el archivo sigue publicado donde esté alojado, porque el backend no lo controla. Se mantiene también al editar: reenviar esa foto sin cambiarla no se vuelve a comprobar | Hoy solo existen en la base de datos de desarrollo, sin usuarios reales, así que no se migran. Si hubiera datos reales anteriores, habría que descargarlas y volver a subirlas a `/api/fotos`, o vaciarlas, antes de poder prometer el borrado |

### El SRS **no** queda cubierto al cien por cien tras este bloque

Con RF-06 cerrado quedaban **tres huecos**. El de los endpoints de alerta se cerró después, en su
propio bloque (§22), así que hoy quedan **dos**: RF-05 y el contacto con la protectora.

> **Corrección.** Este apartado decía que eran dos —RF-05 y el contacto con la protectora— y no era
> cierto: cuatro endpoints de `/api/alertas` diseñados en §4 desde el principio nunca se construyeron,
> y el contrato los daba por hechos. Se detectó al revisar el aviso huérfano de `ALERTA_CERCANA` (§12),
> que depende de uno de ellos.

| Qué falta | Estado |
|---|---|
| **RF-05 — notificaciones.** `NotificacionController` es un esqueleto vacío: no existe `GET /api/notificaciones` ni el `PATCH` de marcado, y nadie emite por `/user/queue/notificaciones`. La mitad de escritura sí está: `NotificacionService` persiste, y los nueve tipos tienen su `CHECK` desde `V6`. Hoy las notificaciones se guardan y **nadie puede leerlas** | Requisito entero sin construir |
| **RF-06 — contacto con la protectora local.** `POST /api/protectoras/{id}/contactar` y `POST /api/protectoras/local/contactar`, diseñados en §9 desde el principio. Este bloque construyó el directorio y la ficha, que es lo que se pidió, pero no ellos | Parte de RF-06 sin construir |
| ~~**Cuatro endpoints de alerta.** `GET /api/alertas` (listado paginado), `GET /api/alertas/{id}` (detalle con avistamientos), `PUT /api/alertas/{id}` (editar) y `DELETE /api/alertas/{id}` (retirar), diseñados en §4. Ninguna fila de la trazabilidad (§14) los cita, pero el resto del contrato cuenta con ellos: §5 remite al detalle para ver los avistamientos de una alerta, §11 compara su retirada con la del administrador y §12 necesita el detalle para descartar avisos huérfanos. Hoy el dueño de una alerta no puede editarla ni retirarla, solo cerrarla~~ | **Cerrado en el bloque de los endpoints de alerta (§22)** |

Los dos primeros no se resuelven escribiendo un endpoint: RF-05 necesita un servicio de push externo
y una tabla de dispositivos, y el contacto una decisión de modelo sobre de qué cuelga un chat sin
publicación. El tercero era escribir endpoints con decisiones previas, y ya está hecho (§22). Los dos
que quedan están descritos en «Trabajo futuro», al final de esta sección.

### Fuera de alcance, pendientes

Lo que queda del SRS son **dos trabajos**, y los dos son más grandes que un endpoint. Los cuatro
endpoints de alerta que el contrato daba por construidos se hicieron en §22. Los dos pendientes están
descritos uno a uno abajo, en «Trabajo futuro».

| Pendiente | Bloque |
|---|---|
| **RF-05 entero, con notificación push real** | Ver «Trabajo futuro» |
| **El contacto directo con la protectora (RF-06)**, con la decisión de modelo que arrastra | Ver «Trabajo futuro» |
| ~~**Los cuatro endpoints de alerta sin construir** (§4)~~ | **Hecho en §22** |
| Dejar que una protectora declare `esPersonaFisica` después de registrarse, para que las anteriores a `V12` puedan corregirlo | Sin bloque asignado |
| Ordenación del directorio por valoración o por número de animales disponibles | RF-06 no lo pide |
| El `countQuery` del listado está escrito aparte del `JOIN FETCH`, así que la condición vive en dos sitios que hay que mantener iguales | Limitación de JPQL con `JOIN FETCH`; cubierto por una prueba que compara el total con las filas devueltas |
| Volver a publicar automáticamente los animales de una protectora rehabilitada tras un bloqueo | Decisión tomada en contra: `RETIRADO` es terminal en todo el proyecto |
| ~~Que el cliente distinga un aviso `ALERTA_CERCANA` huérfano de una alerta terminada con normalidad (§12)~~ | **Resuelto en §22**: el detalle responde `200` con su `estado` para cualquier alerta que existió y `404` solo para la que no; el cliente descarta los `404` |

### Trabajo futuro

#### RF-05 — notificaciones, y la push de verdad

`NotificacionController` es un esqueleto vacío. La mitad de escritura ya está: `NotificacionService`
persiste, y los nueve tipos tienen su `CHECK` desde `V6`. Hoy **las notificaciones se guardan y nadie
puede leerlas**. Falta:

1. `GET /api/notificaciones` paginado, con filtro `leida`, y `PATCH /api/notificaciones/{id}/leida`.
   Es la parte pequeña: la tabla, el índice `idx_notificacion_usuario_leida` y el contrato de §12 ya
   existen.
2. **La entrega en vivo por `/user/queue/notificaciones`**, que hoy no la envía nadie: no hay ni una
   llamada a ese destino en todo el código. El canal está documentado en §13 y el
   `SimpMessagingTemplate` ya se usa para el mapa y para el chat, así que es reutilizar la misma
   maquinaria.
3. **La push real, que es lo que de verdad falta.** Los dos puntos anteriores solo sirven mientras la
   aplicación está abierta: un WebSocket no llega a un móvil con la pantalla apagada, y RF-05 existe
   precisamente para avisar de una alerta cercana a alguien que no está mirando. Eso necesita un
   servicio externo —FCM para Android, APNs para iOS—, y con él tres cosas que el modelo no tiene: una
   tabla de **tokens de dispositivo** por usuario (varios por cuenta, con su alta y su baja cuando el
   proveedor los declara caducados), una **credencial de servidor** en configuración, y una decisión
   sobre **qué tipos se envían por push y cuáles no** —avisar de cada mensaje de chat es la forma más
   rápida de que alguien desactive las notificaciones—. Nada de eso cabe en un endpoint, y por eso se
   enuncia aquí en vez de dejarlo como una fila de una tabla.

#### RF-06 — el contacto directo con la protectora

`POST /api/protectoras/{id}/contactar` y `POST /api/protectoras/local/contactar`, diseñados en §9 desde
el principio. El directorio y la ficha ya permiten **encontrar** a una protectora y ver su email; lo
que falta es abrir la conversación desde dentro de la aplicación, sin salir a un cliente de correo.

No se resuelve escribiendo dos métodos. **Un chat exige hoy un `MATCH_REGISTRO`**, que a su vez exige
una publicación deslizada (`publicacion_id` + `tipo_publicacion`, ambos `NOT NULL`), y «contactar con
una protectora» no cuelga de ninguna publicación. Hacen falta una decisión de modelo y su migración, y
las dos salidas tienen coste:

- **`CHAT.match_id` nulable**, más una forma de resolver los dos participantes cuando no hay match.
  Rompe la invariante que `Chat` documenta como su razón de no tener tabla de participantes: hoy los
  dos extremos se deducen del match, y sin él habría que guardarlos.
- **Un `TipoPublicacion` nuevo** o una publicación implícita, que obliga a revisar los cuatro sitios
  donde `publicacion_id` se lee emparejado con el tipo.

La segunda ruta, `/local/contactar`, añade lo suyo: resolver «la protectora de mi localidad» a partir
del `areaGeografica` del usuario es una comparación de textos libres —«Madrid» contra «Madrid,
Carabanchel»— y hay que decidir qué pasa cuando salen varias o ninguna. `400` si el usuario no tiene
área fijada y `404` si no hay ninguna `ACTIVA` en esa zona es lo que §9 enuncia, pero el criterio de
desempate está sin decidir.

#### Alertas — los cuatro endpoints (hecho en §22)

Las preguntas que quedaban abiertas aquí se resolvieron así; el razonamiento está en §22:

- **El detalle de una alerta que ya no está `ACTIVA`** responde `200` con su estado. Una `ENCONTRADA`
  se ve entera; una `RETIRADA` o `ELIMINADA`, reducida, sin su contenido. `404` solo para la que nunca
  existió.
- **La edición** solo se permite mientras está `ACTIVA` —al contrario que con los animales— y vuelve a
  pasar RF-25 si cambian la especie o la ubicación. Emite `ACTUALIZACION`.
- **La retirada del dueño** no es `ELIMINADA`, sino un estado nuevo, `RETIRADA`: la moderación y la
  decisión del dueño son hechos distintos.
- **La zona del listado** es un círculo `lat`/`lng`/`radio`, como el mapa, y no un texto: las alertas
  no tienen zona escrita.
- **La foto** se valida como estaba previsto.

---

## 21. Bloque de subida de fotos (RF-02, RF-03, RF-07, RF-19, RF-21)

Hasta este bloque la API no recibía archivos: `fotoUrl` era una cadena de hasta 500 caracteres que el
cliente alojaba donde quisiera, y el contrato no decía dónde. Ahora las fotos las sube el cliente al
propio backend, que las guarda en disco —el volumen de Railway en producción— y las sirve. Cómo se usa
está en §1, *Fotos*; aquí, lo que hubo que decidir para construirlo.

### Lo que cambió en el contrato

| Cambio | Antes | Ahora |
|---|---|---|
| `POST /api/fotos` | — | Nuevo. Multipart, parte `archivo`. JPEG o PNG por sus bytes, 5 MB. `201` con `{ "url" }`; `400 FOTO_NO_VALIDA`, `413 FOTO_DEMASIADO_GRANDE` |
| `GET /fotos/{nombre}` | — | Nuevo. Público, sin token. Caché inmutable de un año. `404` si la foto no existe o ya se borró |
| `fotoUrl` en perfil, alerta, avistamiento y animal | Cualquier cadena de hasta 500 caracteres; `javascript:` incluido | Solo una URL devuelta por `POST /api/fotos` **a quien la envía**. Si no, `400 VALIDACION` con `errores.fotoUrl`. Reenviar sin cambios la que ya tenía no se comprueba |
| `fotoUrl` en blanco en `POST /api/alertas` | Se guardaba la cadena vacía | Se guarda `null`, como ya hacía el perfil |
| Baja de cuenta (RF-19) | Vaciaba `foto_url`; la imagen seguía publicada | La foto de perfil se borra de verdad. Las de sus publicaciones, no (§19) |
| Cambiar o quitar la foto de perfil | La anterior seguía publicada para siempre | La anterior se borra |
| Fotos sin usar | — | Una limpieza diaria borra las de más de un día |
| `V14` | — | Tabla `FOTO`: quién subió cada archivo |
| Cupo de subidas | — | 25 al día por usuario; pasado, `429 LIMITE_FOTOS_ALCANZADO` |
| `V15` | — | Tabla `LIMITE_FOTO_DIARIO`: el contador de ese cupo |

### Por qué en el backend y no en Firebase Storage

Se valoraron las dos opciones. Firebase Storage habría reutilizado el proyecto de Firebase que RF-05
necesitará para la push, pero tenía un coste que no se veía a primera vista: sus reglas de seguridad
identifican al usuario con Firebase Auth, y esta API usa su propio JWT. O el bucket quedaba abierto, o
el backend tenía que emitir tokens de Firebase en el login y la aplicación mantener dos sesiones.
Además exige el plan de pago de Firebase, se prueba con un emulador aparte de la suite, y dejaba las
fotos esperando a que se diseñara el cliente Android. Alojarlas aquí cuesta un volumen en Railway y
este bloque, pero es autónomo y se prueba con la suite que ya existe.

### Lo que se guarda no es lo que llega

Toda foto se decodifica y se vuelve a escribir. Eso quita los metadatos —**las coordenadas GPS que
pone el móvil**, que en una foto de perfil hecha en casa publicarían el domicilio— y neutraliza los
archivos que son una imagen válida y otra cosa a la vez. El formato se decide por los bytes, nunca
por el nombre ni el `Content-Type`.

**La reducción a 2048 px se hace al decodificar, no después.** Si el lado mayor la supera, el lector
se salta píxeles (submuestreo) y nunca tiene la imagen entera en memoria: una foto de 50 megapíxeles
de un móvil actual cuesta lo mismo que una pequeña. Se prefirió a rechazar las fotos por encima de un
tamaño, que habría rechazado fotos normales. Las dimensiones se leen de la cabecera antes de nada, y
las absurdas —una cabecera que declara 100.000 × 100.000— se rechazan sin decodificar.

### Quitar una foto: dos pasos, cada uno en su momento

La fila de `FOTO` se borra **dentro** de la transacción —de la baja, del cambio de perfil, de la
limpieza— y el archivo **después del commit**. Al revés, un rollback dejaría una fila apuntando a un
archivo ya borrado. Como `GET /fotos/{nombre}` solo sirve lo que tiene fila, una foto deja de
publicarse en el instante del commit, aunque su archivo tarde en desaparecer. La subida hace lo
simétrico: si su transacción no se confirma, borra el archivo que acababa de escribir.

**Las consultas de uso comparan por el nombre del archivo, no por la URL entera.** La URL lleva la
base pública del entorno que la guardó, y esa base cambia: comparando URLs, un cambio de dominio
habría dado todas las fotos por no usadas, y una limpieza lanzada desde las pruebas —que comparten
la base de datos con el desarrollo y usan otra base pública— habría borrado las fotos del desarrollo.

### La limpieza diaria

A las 4:30, hora de Madrid (`app.fotos.limpieza-cron`), borra lo que tenga más de 24 horas: fotos a
las que no apunta ningún `fotoUrl`, archivos en disco sin fila y temporales de subidas cortadas.
Recorre el disco **antes** que las filas: en el orden contrario vería sin fila los archivos de las
filas que ella misma acaba de quitar y los borraría antes del commit. Recoge también la foto que una
publicación deja de usar al cambiarla, como la de un animal editado en `PUT /api/adopcion/{id}`.

Es la primera tarea programada del proyecto (`@EnableScheduling`). La purga de tokens revocados lo
evitó a propósito haciéndose en cada logout; aquí no se puede, porque encontrar archivos sin fila
obliga a recorrer la carpeta entera.

### El cupo diario de subidas

Sin cupo, un usuario autenticado podía llenar el volumen —que se paga por espacio— subiendo fotos que
nunca usa: la limpieza solo las borra pasado un día, y la factura puede llegar antes de que nadie lo
note. Ahora son 25 subidas al día por usuario (`FOTOS_LIMITE_DIARIO`), de sobra para varias alertas,
avistamientos y cambios de perfil.

Es **el mismo mecanismo que el cupo de matches** (RF-24): un contador por usuario y día natural de
Madrid, creado con `ON CONFLICT DO NOTHING` e incrementado con un `UPDATE` que solo suma si queda
cupo, así que dos subidas simultáneas no pueden pasarse entre las dos. Va en su propia tabla,
`LIMITE_FOTO_DIARIO` (`V15`), y no en `LIMITE_MATCH_DIARIO` con una columna de tipo: habría que
renombrar aquella tabla y su entidad, o guardar fotos en una tabla «de matches», y en los dos casos
tocar el código de RF-24. Tampoco se cuentan las filas de `FOTO` de hoy, que ya existen: ese recuento
baja cuando se borra una foto, y contar antes de insertar deja pasar del límite a dos subidas a la
vez.

El cupo se consume **lo primero**, antes de mirar el archivo, para que quien lo ha agotado no cueste
ni una decodificación. Y como va dentro de la transacción de la subida, una que termina en `400`
deshace su consumo: un archivo rechazado no gasta cupo.

### Sobre `V14`

`FOTO (nombre, usuario_id, tamanyo, created_at)`. La clave primaria es el nombre del archivo, el
mismo que lleva la URL, como el `jti` de `TOKEN_REVOCADO` (`V8`). No guarda el tipo —lo dice la
extensión, elegida por los bytes— ni qué filas usan la foto: eso se consulta en los `foto_url`
existentes, en vez de mantener una segunda copia del mismo hecho en cinco services. Lleva un índice
por `created_at` para la limpieza y ninguno por `usuario_id`, porque nada busca por él.

### Configuración

| Variable | Producción | Por defecto (desarrollo) |
|---|---|---|
| `FOTOS_DIR` | **Obligatoria**: el punto de montaje del volumen | `./uploads` |
| `APP_FOTOS_URL_PUBLICA` | **Obligatoria y `https`**, o la aplicación no arranca | `http://localhost:8080/fotos` |
| `FOTOS_LIMPIEZA_CRON` | Opcional | `0 30 4 * * *`; `-` la desactiva |
| `FOTOS_LIMITE_DIARIO` | Opcional | `25` subidas por usuario y día |

La aplicación tampoco arranca si la carpeta no admite escritura, en vez de descubrirlo con la primera
subida. Para el emulador de Android en local, la base pública es `http://10.0.2.2:8080/fotos`.

### Despliegue

Las fotos viven en un **volumen de Railway** montado en `/data` (`FOTOS_DIR=/data/fotos`): sin él,
cada despliegue las borraría. Los pasos manuales están en el README, *Despliegue en Railway*.

**Railway monta el volumen como root, y la aplicación corre como `spring`.** Sin más, no podía crear
su carpeta y no arrancaba. La imagen lo resuelve con `docker-entrypoint.sh`: arranca como root solo
para crear `/data/fotos` y dársela a `spring`, y ejecuta Java como `spring` con `su-exec`. Se prefirió
a `RAILWAY_RUN_UID=0`, que ejecutaría la aplicación entera como root y tiraría el usuario sin
privilegios del `Dockerfile`. `su-exec` —un paquete de Alpine, no una dependencia del `pom.xml`— hace
`exec`, así que Java es el proceso 1 y recibe directamente la señal de parada.

Se probó en local reproduciendo el montaje de Railway: perfil `prod` y un volumen de Docker montado
como root en `/data`. La imagen anterior no arrancaba (`AccessDeniedException` al crear
`/data/fotos`). La actual arranca con Java como `spring`, sirve una foto subida, la conserva al
volver a crear el contenedor y se para con código 143 (SIGTERM atendido), no 137 (muerte forzada).

**Un archivo muy grande recibe un `413` limpio**, no una conexión cortada. Tomcat solo lee y descarta
2 MB del cuerpo que sobra antes de cortar; con un archivo de 15 MB, el cliente veía la conexión
anulada en vez del `413 FOTO_DEMASIADO_GRANDE`. `server.tomcat.max-swallow-size=20MB` lo extiende, y
una prueba contra el servidor real lo comprueba.

### Limitaciones aceptadas

| Limitación | Por qué se acepta |
|---|---|
| **Las fotos con URL externa anteriores a este bloque quedan fuera del borrado de RF-19** | Ver la fila en §20, *Limitaciones aceptadas*: hoy solo hay datos de desarrollo |
| El cliente tiene que mandar la imagen ya girada | La orientación es un dato EXIF y se pierde al recodificar. Leerla en el servidor exige una dependencia nueva, y Android ya la aplica al comprimir un `Bitmap` |
| La reducción por submuestreo se salta píxeles, y pierde algo de nitidez frente a un escalado suave | A cambio, la memoria queda acotada sea cual sea la foto. Para una pantalla de móvil no se nota |
| Una sola réplica mientras las fotos vivan en un volumen | Railway no comparte un volumen entre instancias. Para la carga de este proyecto sobra; más réplicas exigirían almacenamiento externo |
| `fotoUrl` se guarda como URL absoluta: si cambia el dominio, las URLs guardadas dejan de cargar | La limpieza no se confunde —compara por nombre—, pero las imágenes sí. Se evita con un dominio fijo o propio |
| Una foto de más de un día sin usar puede borrarse justo mientras su dueño la está publicando | Exige usar una subida de hace más de un día en el segundo exacto de la limpieza, de madrugada |
| Comprobar si una foto se usa recorre las columnas `foto_url`, que no tienen índice | Solo se pregunta al cambiar una foto de perfil, al dar de baja una cuenta y una vez al día. A este volumen, un índice costaría más de lo que ahorra |
| `GET /fotos/{nombre}` es público | Una etiqueta `<img>` no manda la cabecera `Authorization`. Lo que impide recorrerlas es que el nombre es un UUID aleatorio |
| Un archivo de más de unos 25 MB recibe una conexión cortada, no un `413` | Para responder, Tomcat tiene que leer y descartar lo que sobra, y leer sin límite cuerpos enormes solo para rechazarlos sería peor. La aplicación de Android no debería mandar nunca algo así: puede comprobar el tamaño antes de subir |

### Fuera de alcance, pendientes

| Pendiente | Bloque |
|---|---|
| WebP, miniaturas, o servir las fotos desde una CDN | Ninguno lo pide un requisito. WebP exige una dependencia nueva |

---

## 22. Bloque de los endpoints de alerta (RF-03, RF-11, RF-14, RF-25)

Cuatro endpoints de `/api/alertas` estaban en el contrato desde el principio y nunca se construyeron:
el listado, el detalle, editar y retirar (§20 lo recogía como hueco). Este bloque los construye, y de
paso corrige dos fallos que salieron al leer el código alrededor y amplía la detección de duplicados.
Cómo se usan está en §4; aquí, lo que hubo que decidir.

### Lo que cambió en el contrato

| Cambio | Antes | Ahora |
|---|---|---|
| `GET /api/alertas` | Diseñado, sin construir | Listado paginado. `ACTIVA` por defecto o `ENCONTRADA`; especie; zona `lat`/`lng`/`radio` |
| `GET /api/alertas/{id}` | Diseñado, sin construir | Detalle con avistamientos. `200` para cualquier alerta que existió, reducido si está retirada; `404` solo si nunca existió |
| `PUT /api/alertas/{id}` | Diseñado, sin construir | Solo el autor y solo `ACTIVA`. RF-25 otra vez si cambian especie o ubicación. Emite `ACTUALIZACION` |
| `DELETE /api/alertas/{id}` | Diseñado como «pasa a `ELIMINADA`» | Pasa a `RETIRADA`, un estado nuevo. Emite `CIERRE` si estaba en el mapa |
| `EstadoAlerta` | `ACTIVA`, `ENCONTRADA`, `ELIMINADA` | Más `RETIRADA` (`V16`) |
| Ventana de RF-25 | La alerta existente avistada en las últimas 24 h | Publicada **o** avistada en las últimas 24 h (§4) |
| `PUT /api/alertas/{id}/cerrar` | Cerraba en cualquier estado | Solo desde `ACTIVA`: el dueño ya no deshace una moderación |
| Moderación de una alerta (`DELETE /api/admin/publicaciones/{id}?tipo=ALERTA`) | No avisaba al mapa | Emite `CIERRE` |
| `GET /api/alertas/mis-alertas` | El contrato decía «página» | Es una lista, y así lo dice ahora: no cambió el código, solo la documentación |

### `RETIRADA` y `ELIMINADA`, dos estados y no uno

El contrato preveía que la retirada del dueño pasara la alerta a `ELIMINADA`, el mismo valor que pone
el administrador al moderar. Se separaron. **Son hechos distintos** —una decisión neutra del dueño y
una sanción— y, si compartieran valor, la diferencia se perdería en cada fila sin forma de
reconstruirla: no habría estadísticas de moderación, ni reincidencia, ni recurso contra una retirada.
**Solo se podían separar ahora**: como el `DELETE` del dueño no existía, todas las `ELIMINADA` que hay
son del administrador y `V16` no tuvo que migrar ningún dato.

La consecuencia de diseño es que **nada de lo que hace el dueño pisa una `ELIMINADA`**: no la cierra,
no la edita, y retirarla responde `204` sin cambiarla. El administrador sí puede moderar una alerta
ya `RETIRADA` o `ENCONTRADA`: la sanción es la información más fuerte.

Los animales en adopción no hacen esta distinción: la protectora y el administrador ponen los dos
`RETIRADO`. Es una asimetría conocida, no un modelo a seguir; no se ha tocado.

### El detalle de lo retirado responde, pero no vuelve a publicarlo

`404` se reserva para un id que nunca existió, y eso es lo que permite al cliente distinguir un aviso
`ALERTA_CERCANA` huérfano de uno viejo (§12). Pero devolver entera una alerta moderada desharía la
moderación para cualquiera que tenga el id. Por eso una `RETIRADA` o `ELIMINADA` responde `200` con
solo `id`, `especie`, `estado` y `createdAt`, sin descripción, foto, ubicación, autor ni avistamientos.
Un switch exhaustivo sobre el estado decide qué forma lleva: un estado nuevo no compila hasta que
alguien decida qué enseña.

### RF-25 al editar, y por qué la ventana se amplió

**Si cambian la especie o la ubicación, la edición vuelve a pasar RF-25**, sin contarse a sí misma.
Sin ello la detección se esquivaba en dos pasos: publicar lejos y mover después la alerta encima de
otra, o publicar con otra especie y cambiarla luego. No hay umbral de «cambio significativo»: sería un
número inventado, y diez movimientos de 50 m suman 500. Si cambian otros campos no se repite.

**Editar la fecha no la dispara, y no por descuido.** La detección compara la alerta nueva con las
fechas de las *existentes*, nunca con las suyas; repetirla al cambiar la fecha daría el mismo
resultado, y solo añadiría falsos positivos contra vecinas legítimas. Pero analizarlo destapó el hueco
de verdad, que estaba en la publicación: **una alerta publicada hoy sobre un animal visto hace dos días
no bloqueaba nunca a nadie**, el caso más común. Por eso la ventana pasó a mirar también cuándo se
**publicó** la alerta existente (`created_at`), que el servidor pone y nadie edita. Con eso, ni atrasar
la fecha al editar ni publicar tarde sirve para colar un duplicado. A cambio, ahora se consideran
duplicadas más alertas que antes.

### La zona del listado es un círculo

El contrato preveía un filtro `zona` de texto, como el del catálogo de adopción, pero las alertas no
tienen zona escrita: tienen el punto donde se vio al animal. El `areaGeografica` del autor es su área
de perfil, opcional, y no dice dónde se perdió. Así que el filtro es el mismo de `/mapa`, `lat`, `lng`
y `radio`, dentro de la misma `Specification` que los demás filtros con `ST_DistanceSphere`. No usa el
índice GiST —desde Criteria no se puede escribir el `CAST` a `geography` que usa el mapa—, y se acepta:
el listado va paginado y filtra antes por estado.

### Dos fallos que se corrigieron por el camino

- **`cerrar` deshacía la moderación.** No miraba el estado, así que el dueño de una alerta `ELIMINADA`
  podía marcarla como `ENCONTRADA` y volver a exponerla. Con el listado de resueltas y el detalle
  completo de las `ENCONTRADA`, eso se habría convertido en una vía real para esquivar al
  administrador. Ahora solo cierra desde `ACTIVA`.
- **La moderación no quitaba el marcador del mapa.** `AdminService` cambiaba el estado sin emitir
  `CIERRE`, y la alerta seguía en el mapa de quien lo tuviera abierto. La transición pasó a
  `AlertaService.retirarPorModeracion`, que es quien emite los eventos del mapa, como ya se hizo con
  la retirada de animales.

### Nada se borra en cascada

Ni cerrar, ni retirar, ni moderar borran nada: sus avistamientos, los matches sobre ellos, sus chats y
sus mensajes se quedan. No hizo falta código para conseguirlo: el feed de avistamientos y los matches
ya solo admitían alertas `ACTIVA`, así que los avistamientos de una alerta terminada salen del feed
sin tocarlos. Una prueba de extremo a extremo lo comprueba: el chat abierto sobre un avistamiento
sigue legible y admite mensajes después de retirar su alerta.

### Sobre `V16`

Solo rehace `alerta_estado_check` con el valor nuevo. No acompaña a ningún cambio de columna, pero sin
ella el primer `UPDATE` a `RETIRADA` fallaría en ejecución, y `ddl-auto=validate` no comprueba los
`CHECK`.

### Limitaciones aceptadas

| Limitación | Por qué se acepta |
|---|---|
| Al editar, la alerta no vuelve a avisar a la zona (RF-05) | El aviso es de la publicación; repetirlo en cada corrección sería ruido |
| El listado no usa el índice espacial | Desde Criteria no se puede escribir el `CAST` a `geography`. El listado va paginado y filtra antes por estado |
| El detalle trae todos los avistamientos visibles de la alerta, sin paginar | Una alerta recibe pocos; si alguna llegara a cientos habría que paginarlos dentro del detalle |
| Con la ventana ampliada de RF-25 se rechazan más alertas que antes | Es el precio de cerrar los dos huecos; el `409` sigue llevando la alerta existente para redirigir al usuario |
| Los animales siguen sin distinguir retirada por la protectora de retirada por el administrador | Fuera del alcance de este bloque; separarlo exigiría su propia migración y decidir qué pasa con los `RETIRADO` actuales, que ya no se pueden atribuir |
