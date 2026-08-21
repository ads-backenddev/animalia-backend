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

---

## 2. `/api/auth` — Registro, sesión y contraseña

RF-01, RF-13, RF-16, RF-17.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/auth/registro/ciudadano` | Registra un usuario con rol CIUDADANO. La contraseña se guarda con BCrypt (RNF-03) | Público | `RegistroCiudadanoRequest`: `nombre`, `email`, `password`, `telefono?`, `areaGeografica?`, `idioma?` | `UsuarioResponse` | `201`, `400`, `409` |
| `POST` | `/api/auth/registro/protectora` | Registra una protectora. Queda en estado `PENDIENTE` hasta que el administrador la verifica, y no puede iniciar sesión hasta entonces | Público | `RegistroProtectoraRequest`: `nombre`, `email`, `password`, `telefono?`, `nombreOrganizacion`, `direccion`, `zonaGeografica` | `ProtectoraResponse` con `estado: PENDIENTE` | `201`, `400`, `409` |
| `POST` | `/api/auth/login` | Autentica y devuelve el JWT. Debe responder en menos de 2 s (RNF-01) | Público | `LoginRequest`: `email`, `password` | `LoginResponse`: `token`, `tipo: "Bearer"`, `expiraEn`, `usuario` | `200`, `400`, `401`, `403` |
| `POST` | `/api/auth/logout` | Cierra la sesión del cliente e invalida el token en la lista de revocados | Autenticado | — | — | `204`, `401` |
| `POST` | `/api/auth/recuperar-password` | Envía al email registrado un enlace con token temporal (1 h). Responde `204` siempre, exista o no el email, para no revelar qué cuentas están registradas | Público | `RecuperarPasswordRequest`: `email` | — | `204`, `400` |
| `POST` | `/api/auth/restablecer-password` | Fija la nueva contraseña a partir del token del enlace | Público | `RestablecerPasswordRequest`: `token`, `nuevaPassword` | — | `204`, `400` |

**`403` en login:** cuenta bloqueada por el administrador (`activo = false`) o protectora en estado
`PENDIENTE` / `RECHAZADA`.

---

## 3. `/api/usuarios` — Perfil propio

RF-02, RF-18, RF-19.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/usuarios/me` | Perfil completo del usuario autenticado | Autenticado | — | `UsuarioResponse` | `200`, `401` |
| `PUT` | `/api/usuarios/me` | Personaliza el perfil: foto, nombre, área geográfica y teléfono | Autenticado | `ActualizarPerfilRequest`: `nombre`, `telefono?`, `areaGeografica?`, `fotoUrl?` | `UsuarioResponse` | `200`, `400`, `401` |
| `PATCH` | `/api/usuarios/me/idioma` | Cambia el idioma de la aplicación | Autenticado | `CambiarIdiomaRequest`: `idioma` (`es`, `en`, `ca`, `gl`, `eu`) | `UsuarioResponse` | `200`, `400`, `401` |
| `DELETE` | `/api/usuarios/me` | Elimina la cuenta y **todos** los datos asociados, en cumplimiento del RGPD (RF-19, RNF-10) | Autenticado | — | — | `204`, `401` |

**Borrado RGPD:** el `DELETE` arrastra alertas, avistamientos, matches, mensajes, valoraciones,
reportes, notificaciones y contadores de límite diario del usuario.

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
| `GET` | `/api/alertas` | Lista paginada de alertas activas. Filtros: `especie`, `estado`, `zona` | Autenticado | — | Página de `AlertaResponse` | `200`, `401` |
| `GET` | `/api/alertas/{id}` | Detalle de una alerta con sus avistamientos asociados | Autenticado | — | `AlertaDetalleResponse` | `200`, `401`, `404` |
| `PUT` | `/api/alertas/{id}` | Modifica una alerta propia | Propietario | `ActualizarAlertaRequest`: mismos campos que la creación | `AlertaResponse` | `200`, `400`, `401`, `403`, `404` |
| `DELETE` | `/api/alertas/{id}` | Retira una alerta propia: pasa a estado `ELIMINADA` y desaparece del mapa | Propietario | — | — | `204`, `401`, `403`, `404` |
| `GET` | `/api/alertas/mapa` | Alertas `ACTIVA` con ubicación, para el mapa interactivo en tiempo real. Filtro por área: `lat`, `lng`, `radio` (metros) — **los tres o ninguno**; `radio` admite 50 km como máximo. Sin filtro devuelve todas las activas. Debe soportar 100 usuarios simultáneos (RNF-02) | Autenticado | — | Lista de `AlertaMapaResponse`: `id`, `especie`, `fotoUrl`, `ubicacion`, `fechaAvistamiento`, `estado` | `200`, `400`, `401` |
| `GET` | `/api/alertas/mis-alertas` | Alertas publicadas por el usuario autenticado, en cualquier estado | Autenticado | — | Página de `AlertaResponse` | `200`, `401` |
| `PUT` | `/api/alertas/{id}/cerrar` | Marca la mascota como encontrada: estado `ENCONTRADA`, se emite `CIERRE` por `/topic/mapa` y desaparece del mapa (RF-14) | Propietario | — | `AlertaResponse` | `200`, `401`, `403`, `404` |

### Detección de alertas duplicadas (RF-25)

`POST /api/alertas` **bloquea** la publicación y responde `409` cuando ya existe una alerta que
cumple las tres condiciones a la vez:

1. misma `especie`,
2. `ultimaUbicacion` dentro de un radio de **500 m** (consulta espacial PostGIS `ST_DWithin`),
3. `fechaAvistamiento` dentro de las **últimas 24 h**.

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
`AlertaDetalleResponse` de `GET /api/alertas/{id}`.

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
| `GET` | `/api/adopcion/{id}` | Detalle de un animal con los datos de su protectora | Autenticado | — | `AnimalAdopcionDetalleResponse` | `200`, `401`, `404` |
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
| `GET` | `/api/protectoras` | Listado de protectoras `ACTIVA`. Filtro `zona` | Autenticado | — | Página de `ProtectoraResponse` | `200`, `401` |
| `GET` | `/api/protectoras/{id}` | Detalle de una protectora con su valoración media | Autenticado | — | `ProtectoraDetalleResponse` | `200`, `401`, `404` |
| `GET` | `/api/protectoras/{id}/animales` | Animales en adopción de esa protectora | Autenticado | — | Página de `AnimalAdopcionResponse` | `200`, `401`, `404` |
| `POST` | `/api/protectoras/{id}/contactar` | Abre un contacto con una protectora concreta al encontrar un animal perdido (RF-06) | Autenticado | `ContactarProtectoraRequest`: `mensaje`, `alertaId?`, `avistamientoId?` | `ChatResumenResponse` | `201`, `400`, `401`, `404` |
| `POST` | `/api/protectoras/local/contactar` | Contacta con la protectora de la localidad del usuario, resolviéndola a partir de su `areaGeografica` sin que tenga que buscarla. Es la «opción específica» que pide RF-06 | Autenticado | `ContactarProtectoraRequest` | `ChatResumenResponse` | `201`, `400`, `401`, `404` |
| `POST` | `/api/protectoras/{id}/valoracion` | Valora la protectora de 1 a 5 estrellas tras una adopción o contacto (RF-23). Recalcula `valoracionMedia` | Autenticado | `CrearValoracionRequest`: `puntuacion` (1-5) | `ValoracionResponse` | `201`, `400`, `401`, `403`, `404`, `409` |
| `GET` | `/api/protectoras/panel` | Panel de gestión de la protectora autenticada: sus animales y los usuarios que han querido contactar (RF-10) | `PROTECTORA` | — | `PanelProtectoraResponse`: `animales[]`, `contactos[]`, `valoracionMedia`, `totalAdopciones` | `200`, `401`, `403` |

**`403` en valoración:** el usuario no ha tenido adopción ni contacto previo con esa protectora.
**`409`:** ya la había valorado.

**En `/local/contactar`:** `400` si el usuario no tiene `areaGeografica` fijada en su perfil, `404` si
no hay ninguna protectora `ACTIVA` en esa zona. El segmento literal `local` tiene prioridad sobre el
patrón `{id}`, así que ambas rutas conviven sin ambigüedad.

---

## 10. `/api/reportes` — Reportes de contenido

RF-26.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/reportes` | Reporta una publicación o un usuario indicando el motivo. Queda en estado `PENDIENTE` para su revisión | Autenticado | `CrearReporteRequest`: `tipoObjetivo` (`PUBLICACION` \| `USUARIO`), `objetivoId`, `tipoContenido?`, `motivo` | `ReporteResponse` | `201`, `400`, `401`, `404`, `409` |

Valores de `motivo`: `CONTENIDO_INAPROPIADO`, `SPAM`, `INFORMACION_FALSA`, `ACOSO`,
`ANIMAL_EN_PELIGRO`, `OTRO`. **`409`** si el usuario ya había reportado ese mismo objetivo.

**`tipoContenido` es obligatorio cuando `tipoObjetivo` es `PUBLICACION`** y debe omitirse cuando es
`USUARIO`. Valores: `ALERTA`, `AVISTAMIENTO`, `ADOPCION`. Sin él, `objetivoId` sería ambiguo: hay tres
tablas de publicaciones y el id solo es único dentro de cada una. Incumplirlo devuelve `400`.

---

## 11. `/api/admin` — Administración

RF-11. **Todos los endpoints requieren rol `ADMIN`.**

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/admin/usuarios` | Listado paginado de usuarios. Filtros: `rol`, `activo`, `email` | `ADMIN` | — | Página de `UsuarioAdminResponse` | `200`, `401`, `403` |
| `PATCH` | `/api/admin/usuarios/{id}/bloquear` | Bloquea la cuenta (`activo = false`): no podrá iniciar sesión | `ADMIN` | `BloquearUsuarioRequest`: `motivo?` | — | `204`, `401`, `403`, `404` |
| `PATCH` | `/api/admin/usuarios/{id}/desbloquear` | Reactiva la cuenta | `ADMIN` | — | — | `204`, `401`, `403`, `404` |
| `GET` | `/api/admin/protectoras/pendientes` | Protectoras en estado `PENDIENTE` esperando verificación | `ADMIN` | — | Página de `ProtectoraResponse` | `200`, `401`, `403` |
| `PATCH` | `/api/admin/protectoras/{id}/aprobar` | Verifica la protectora: pasa a `ACTIVA` y ya puede iniciar sesión y publicar | `ADMIN` | — | `ProtectoraResponse` | `200`, `401`, `403`, `404` |
| `PATCH` | `/api/admin/protectoras/{id}/rechazar` | Rechaza la solicitud: pasa a `RECHAZADA` | `ADMIN` | `RechazarProtectoraRequest`: `motivo?` | `ProtectoraResponse` | `200`, `401`, `403`, `404` |
| `GET` | `/api/admin/reportes` | Reportes recibidos. Filtro `estado` (`PENDIENTE` \| `REVISADO`) | `ADMIN` | — | Página de `ReporteAdminResponse` | `200`, `401`, `403` |
| `PATCH` | `/api/admin/reportes/{id}/revisado` | Cierra un reporte marcándolo como `REVISADO` | `ADMIN` | — | — | `204`, `401`, `403`, `404` |
| `DELETE` | `/api/admin/publicaciones/{id}?tipo={tipo}` | Elimina cualquier publicación (RF-11) | `ADMIN` | — | — | `204`, `400`, `401`, `403`, `404` |

**El parámetro `tipo` es obligatorio** y toma los valores del enum `TipoContenido`: `ALERTA`,
`AVISTAMIENTO` o `ADOPCION`. Sin él la petición devuelve `400`, porque el id de una publicación solo
es único dentro de su propia tabla y `{id}` por sí solo no identifica el recurso. No se reutiliza
`TipoPublicacion` porque ese enum solo cubre los tipos sobre los que se puede hacer match, donde
`ALERTA` no es válido.

---

## 12. `/api/notificaciones` — Notificaciones

RF-05.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/notificaciones` | Notificaciones del usuario, más recientes primero. Filtro `leida` | Autenticado | — | Página de `NotificacionResponse` | `200`, `401` |
| `PATCH` | `/api/notificaciones/{id}/leida` | Marca una notificación como leída | Propietario | — | — | `204`, `401`, `403`, `404` |

Tipos de notificación: `ALERTA_CERCANA`, `NUEVO_MATCH`, `NUEVO_MENSAJE`, `PROTECTORA_VERIFICADA`,
`ADOPCION_CONFIRMADA`, `REPORTE_RESUELTO`.

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
| `/user/queue/notificaciones` | Servidor → cliente | Notificaciones del usuario, incluidas las alertas registradas en su área geográfica (RF-05). **Pendiente de implementar**: hoy las notificaciones solo se persisten y se consultan por `GET /api/notificaciones` | Autenticado | `NotificacionResponse` |

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
| RF-02 | `PUT /api/usuarios/me` |
| RF-03 | `POST /api/alertas` |
| RF-04 | `GET /api/alertas/mapa`, destino `/topic/mapa` |
| RF-05 | `GET /api/notificaciones`, destino `/user/queue/notificaciones` |
| RF-06 | `POST /api/protectoras/{id}/contactar` y `POST /api/protectoras/local/contactar` |
| RF-07 | `POST`, `PUT` y `DELETE` sobre `/api/adopcion` |
| RF-08 | `POST /api/matches` con `tipoPublicacion=AVISTAMIENTO` |
| RF-09 | `GET /api/adopcion/feed`, `POST /api/matches` con `tipoPublicacion=ADOPCION` |
| RF-10 | `GET /api/protectoras/panel` |
| RF-11 | Todo `/api/admin` |
| RF-12 | `/api/chats/**`, destino `/topic/chat/{chatId}` |
| RF-13 | `POST /api/auth/login` |
| RF-14 | `PUT /api/alertas/{id}/cerrar` |
| RF-15 | `POST /api/matches` con `interesado: false`, `GET /api/avistamientos/feed`, `GET /api/adopcion/feed` |
| RF-16 | `POST /api/auth/logout` |
| RF-17 | `POST /api/auth/recuperar-password`, `POST /api/auth/restablecer-password` |
| RF-18 | `PATCH /api/usuarios/me/idioma` |
| RF-19 | `DELETE /api/usuarios/me` |
| RF-20 | `PUT /api/adopcion/{id}/adoptado` |
| RF-21 | `POST /api/avistamientos` (campo `fotoUrl`) |
| RF-22 | `GET /api/adopcion` (filtros `especie`, `tamanyo`, `zona`) |
| RF-23 | `POST /api/protectoras/{id}/valoracion` |
| RF-24 | `GET /api/matches/limite`, respuesta `429` de `POST /api/matches` |
| RF-25 | Respuesta `409` de `POST /api/alertas` |
| RF-26 | `POST /api/reportes` |

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
| `PATCH /api/admin/reportes/{id}/revisado` | El enum `EstadoReporte` tiene el valor `REVISADO`, que necesita una transición |
| `GET /api/avistamientos/{id}` | Consultar el detalle de un avistamiento |
| `POST /api/chats/{id}/mensajes`, `PUT /api/chats/{id}/mensajes/leidos` | El campo `leido` de MENSAJE necesita una transición; el POST es la alternativa REST al WebSocket |
| Parámetro `?tipo=` en `DELETE /api/admin/publicaciones/{id}` | Las publicaciones son de tres tablas distintas: el id solo no identifica el recurso |

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

### Dos cosas que se decidieron NO cambiar

Se estudiaron en el mismo bloque y se dejaron como están. Conviene que quede escrito para que no se
retomen por inercia ni se lean como descuidos:

**La referencia polimórfica de `MATCH_REGISTRO` se queda.** `publicacion_id` apunta a
`AVISTAMIENTO` o a `ANIMAL_ADOPCION` según `tipo_publicacion`, sin clave ajena que lo respalde. Las
dos alternativas —un arco exclusivo con dos columnas nulables y un `CHECK`, o un supertipo
`PUBLICACION` del que hereden ambas— resolverían la integridad, y la segunda es lo que se elegiría
empezando de cero. Ninguna se ha aplicado porque **ningún requisito la exige** y el riesgo no está
compensado: obliga a reescribir las dos entidades, sus repositorios y las consultas, con 321 pruebas
apoyadas en la forma actual. La debilidad está contenida: la referencia va siempre emparejada con el
tipo en los tres sitios donde se lee (`ChatRepository`, `CatalogoAdopcionSpec` y
`MatchService.resolverAutor`), y ese último es un `switch` exhaustivo sin rama por defecto, así que
un tipo nuevo rompe la compilación en lugar de colarse en ejecución. Se reabre si aparece un tercer
tipo deslizable o un incidente real de integridad.

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
| Borrado / anonimización de usuario (RF-19): nueve claves ajenas apuntan a `USUARIO` y ninguna dice qué hacer al borrarlo | 10 |
| El día de la cuota está fijado a `Europe/Madrid` (§6): correcto para la península, no para Canarias. Contarla por zona del cliente exige que la envíe y guardarla junto al contador | RF-24 |
| `ANIMAL_ADOPCION` no lleva `@Version`: confirmar la adopción y retirar el animal a la vez dejan ganar a la última escritura. Los dos desenlaces son estados legales, así que no corrompe nada — mismo criterio que la comprobación de duplicados de RF-25 | Baja prioridad, sin bloque asignado |
| Índices funcionales de `ANIMAL_ADOPCION` sobre `lower(especie)` y `lower(zona_geografica)` | Cuando el catálogo tenga volumen que lo justifique (ver V4) |
