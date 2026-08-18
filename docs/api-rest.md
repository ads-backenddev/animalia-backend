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
  "timestamp": "2026-03-14T18:22:05",
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
| `POST` | `/api/matches` | Registra un deslizamiento. Con `interesado: true` es un match: incrementa el contador diario, abre el chat asociado (RF-12) y avisa al dueño de la alerta. Con `false` es un descarte (RF-15) | Autenticado | `CrearMatchRequest`: `publicacionId`, `tipoPublicacion` (`AVISTAMIENTO` \| `ADOPCION`), `interesado` | `MatchResponse`: `id`, `publicacionId`, `tipoPublicacion`, `interesado`, `chatId?`, `createdAt` | `201`, `400`, `401`, `403`, `404`, `409`, `429` |
| `GET` | `/api/matches/mios` | Matches del usuario autenticado, más recientes primero. Solo los positivos: los descartes comparten tabla pero no son matches | Autenticado | — | Lista de `MatchResponse` | `200`, `401` |
| `GET` | `/api/matches/limite` | Estado del límite diario, para que el cliente lo muestre antes de agotarlo | Autenticado | — | `LimiteMatchResponse` | `200`, `401` |

### Los dos sentidos del swipe van por el mismo endpoint

Un solo `POST /api/matches` cubre las dos direcciones, distinguiéndose por `interesado`, igual que
se distinguen en la tabla `MATCH_REGISTRO`:

| `interesado` | Significado | Abre chat | Notifica | Consume cuota diaria |
|---|---|---|---|---|
| `true` | Match (RF-08) | Sí | Sí, `NUEVO_MATCH` al dueño de la alerta | Sí |
| `false` | Descarte (RF-15) | No | No | No |

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

**`400` con código `TIPO_PUBLICACION_NO_DISPONIBLE`** si se pide `tipoPublicacion: ADOPCION`: por
ahora solo se admite `AVISTAMIENTO`; las adopciones llegarán con su propio bloque.

El orden de las comprobaciones importa: la cuota se consume en último lugar, después de validar
todo lo demás. Al revés, un swipe repetido —que acaba en `409`— le habría gastado al usuario un
match del día.

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
| `PATCH` | `/api/chats/{id}/mensajes/leidos` | Marca como leídos todos los mensajes recibidos en la conversación | Participante | — | — | `204`, `401`, `403`, `404` |

`Participante` significa que el usuario autenticado es una de las dos partes del match que generó el
chat; en caso contrario `403`.

---

## 8. `/api/adopcion` — Catálogo de adopción

RF-07, RF-09, RF-20, RF-22.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/adopcion` | Catálogo paginado de animales `DISPONIBLE`. Filtros de RF-22: `especie`, `tamanyo` (`PEQUENO` \| `MEDIANO` \| `GRANDE`), `zona` | Autenticado | — | Página de `AnimalAdopcionResponse` | `200`, `400`, `401` |
| `GET` | `/api/adopcion/{id}` | Detalle de un animal con los datos de su protectora | Autenticado | — | `AnimalAdopcionDetalleResponse` | `200`, `401`, `404` |
| `GET` | `/api/adopcion/feed` | Feed para el swipe de adopciones, excluyendo los ya deslizados (RF-09, RF-15) | Autenticado | — | Lista de `AnimalAdopcionResponse` | `200`, `401` |
| `POST` | `/api/adopcion` | Publica un animal en adopción | `PROTECTORA` | `CrearAnimalRequest`: `nombre`, `especie`, `tamanyo`, `descripcion`, `fotoUrl?`, `zonaGeografica` | `AnimalAdopcionResponse` | `201`, `400`, `401`, `403` |
| `PUT` | `/api/adopcion/{id}` | Modifica un animal de la propia protectora | `PROTECTORA` + Propietario | `ActualizarAnimalRequest` | `AnimalAdopcionResponse` | `200`, `400`, `401`, `403`, `404` |
| `DELETE` | `/api/adopcion/{id}` | Retira un animal del catálogo | `PROTECTORA` + Propietario | — | — | `204`, `401`, `403`, `404` |
| `PATCH` | `/api/adopcion/{id}/adoptado` | Confirma la adopción: el animal pasa a estado `ADOPTADO` (RF-20) | `PROTECTORA` + Propietario | `ConfirmarAdopcionRequest`: `usuarioAdoptanteId?` | `AnimalAdopcionResponse` | `200`, `401`, `403`, `404` |

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
| `/topic/chat/{chatId}` | Bidireccional (envío a `/app/chat/{chatId}`) | Mensajería en tiempo real de una conversación (RF-12). **Pendiente de implementar** | Participante del chat | Envío: `EnviarMensajeRequest`. Recepción: `MensajeResponse` |
| `/user/queue/notificaciones` | Servidor → cliente | Notificaciones del usuario, incluidas las alertas registradas en su área geográfica (RF-05). **Pendiente de implementar**: hoy las notificaciones solo se persisten y se consultan por `GET /api/notificaciones` | Autenticado | `NotificacionResponse` |

El prefijo `/user/` no es una ruta más: Spring lo resuelve contra el `Principal` de la sesión STOMP
y entrega el mensaje **solo** a la conexión de ese usuario, aunque todos se suscriban literalmente a
`/user/queue/notificaciones`. El servidor envía con
`convertAndSendToUser(usuario, "/queue/notificaciones", …)`.

> Para que ese prefijo funcione, la sesión WebSocket necesita un `Principal`.
> `JwtHandshakeInterceptor` deja hoy el `UsuarioPrincipal` en los atributos de la sesión, que basta
> para `/topic/mapa` (difusión a cualquier autenticado) pero no para `/user/…`. Antes de implementar
> RF-05 y RF-12 hay que propagarlo, sobrescribiendo `determineUser` en un `DefaultHandshakeHandler`.
> El chat, además, necesita comprobar en la suscripción que quien se suscribe a
> `/topic/chat/{chatId}` es participante de ese chat: el destino es público entre autenticados.

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
| RF-07 | `POST /api/adopcion` |
| RF-08 | `POST /api/matches` con `tipoPublicacion=AVISTAMIENTO` |
| RF-09 | `POST /api/matches` con `tipoPublicacion=ADOPCION` |
| RF-10 | `GET /api/protectoras/panel` |
| RF-11 | Todo `/api/admin` |
| RF-12 | `/api/chats/**`, destino `/topic/chat/{chatId}` |
| RF-13 | `POST /api/auth/login` |
| RF-14 | `PUT /api/alertas/{id}/cerrar` |
| RF-15 | `POST /api/matches/descartar`, `GET /api/avistamientos/feed`, `GET /api/adopcion/feed` |
| RF-16 | `POST /api/auth/logout` |
| RF-17 | `POST /api/auth/recuperar-password`, `POST /api/auth/restablecer-password` |
| RF-18 | `PATCH /api/usuarios/me/idioma` |
| RF-19 | `DELETE /api/usuarios/me` |
| RF-20 | `PATCH /api/adopcion/{id}/adoptado` |
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
| `POST /api/matches/descartar` | RF-15 (swipe de descarte) no tenía ruta en el resumen |
| `GET /api/adopcion/feed` | RF-09 + RF-15: el swipe de adopción necesita su propio feed, igual que el de avistamientos |
| `POST /api/protectoras/local/contactar` | RF-06 pide una «opción específica» para la protectora de la localidad, que no puede resolverse con un `{id}` en la ruta porque el usuario aún no lo conoce |
| `GET /api/admin/usuarios` | RF-11 dice «gestionar a todos los usuarios», lo que exige poder listarlos |
| `PATCH /api/admin/reportes/{id}/revisado` | El enum `EstadoReporte` tiene el valor `REVISADO`, que necesita una transición |
| `GET /api/avistamientos/{id}` | Consultar el detalle de un avistamiento |
| `POST /api/chats/{id}/mensajes`, `PATCH /api/chats/{id}/mensajes/leidos` | El campo `leido` de MENSAJE necesita una transición; el POST es la alternativa REST al WebSocket |
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
