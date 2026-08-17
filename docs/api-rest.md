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
| `GET` | `/api/usuarios/{id}` | Perfil público reducido de otro usuario: nombre, foto y área. Nunca expone email ni teléfono | Autenticado | — | `UsuarioPublicoResponse` | `200`, `401`, `404` |

**Borrado RGPD:** el `DELETE` arrastra alertas, avistamientos, matches, mensajes, valoraciones,
reportes, notificaciones y contadores de límite diario del usuario.

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
| `GET` | `/api/alertas/mapa` | Alertas `ACTIVA` con ubicación, para el mapa interactivo en tiempo real. Filtro por área: `lat`, `lng`, `radio` (metros). Debe soportar 100 usuarios simultáneos (RNF-02) | Autenticado | — | Lista de `AlertaMapaResponse`: `id`, `especie`, `fotoUrl`, `ubicacion`, `fechaAvistamiento` | `200`, `400`, `401` |
| `GET` | `/api/alertas/mis-alertas` | Alertas publicadas por el usuario autenticado, en cualquier estado | Autenticado | — | Página de `AlertaResponse` | `200`, `401` |
| `PATCH` | `/api/alertas/{id}/cerrar` | Marca la mascota como encontrada: estado `ENCONTRADA` y deja de notificarse en el mapa (RF-14) | Propietario | — | `AlertaResponse` | `200`, `401`, `403`, `404` |

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
| `POST` | `/api/avistamientos` | Registra un avistamiento sobre una alerta, con foto para la identificación visual del match (RF-21) | Autenticado | `CrearAvistamientoRequest`: `alertaId`, `descripcion`, `fotoUrl?`, `ubicacion{latitud,longitud}` | `AvistamientoResponse` | `201`, `400`, `401`, `404` |
| `GET` | `/api/avistamientos/feed` | Feed para el swipe: avistamientos candidatos ordenados por proximidad, excluyendo los ya deslizados por el usuario (RF-15) | Autenticado | — | Lista de `AvistamientoFeedResponse` | `200`, `401` |
| `GET` | `/api/avistamientos/{id}` | Detalle de un avistamiento | Autenticado | — | `AvistamientoResponse` | `200`, `401`, `404` |
| `GET` | `/api/avistamientos?alertaId={id}` | Avistamientos registrados sobre una alerta concreta | Autenticado | — | Página de `AvistamientoResponse` | `200`, `401`, `404` |

---

## 6. `/api/matches` — Matches y límite diario

RF-08, RF-09, RF-15, RF-24.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/matches` | Registra un match sobre un avistamiento o un animal en adopción, incrementa el contador diario y abre el chat asociado (RF-12) | Autenticado | `CrearMatchRequest`: `publicacionId`, `tipoPublicacion` (`AVISTAMIENTO` \| `ADOPCION`) | `MatchResponse`: `id`, `chatId`, `tipoPublicacion`, `createdAt` | `201`, `400`, `401`, `404`, `409`, `429` |
| `GET` | `/api/matches/mios` | Matches realizados por el usuario autenticado | Autenticado | — | Página de `MatchResponse` | `200`, `401` |
| `GET` | `/api/matches/limite` | Estado del límite diario, para que el cliente lo muestre antes de agotarlo | Autenticado | — | `LimiteMatchResponse`: `limiteDiario`, `consumidos`, `restantes`, `reiniciaEn` | `200`, `401` |
| `POST` | `/api/matches/descartar` | Descarta una publicación sin interés (swipe negativo): no vuelve a aparecer en el feed y no consume cuota (RF-15) | Autenticado | `DescartarPublicacionRequest`: `publicacionId`, `tipoPublicacion` | — | `204`, `400`, `401`, `404` |

**`409`** si el usuario ya había hecho match con esa publicación.

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
| `POST` | `/api/protectoras/{id}/contactar` | Abre un contacto con la protectora al encontrar un animal perdido. Con `soloLocalidad=true` la resuelve automáticamente a partir del área del usuario (RF-06) | Autenticado | `ContactarProtectoraRequest`: `mensaje`, `alertaId?`, `avistamientoId?` | `ChatResumenResponse` | `201`, `400`, `401`, `404` |
| `POST` | `/api/protectoras/{id}/valoracion` | Valora la protectora de 1 a 5 estrellas tras una adopción o contacto (RF-23). Recalcula `valoracionMedia` | Autenticado | `CrearValoracionRequest`: `puntuacion` (1-5) | `ValoracionResponse` | `201`, `400`, `401`, `403`, `404`, `409` |
| `GET` | `/api/protectoras/panel` | Panel de gestión de la protectora autenticada: sus animales y los usuarios que han querido contactar (RF-10) | `PROTECTORA` | — | `PanelProtectoraResponse`: `animales[]`, `contactos[]`, `valoracionMedia`, `totalAdopciones` | `200`, `401`, `403` |

**`403` en valoración:** el usuario no ha tenido adopción ni contacto previo con esa protectora.
**`409`:** ya la había valorado.

---

## 10. `/api/reportes` — Reportes de contenido

RF-26.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `POST` | `/api/reportes` | Reporta una publicación o un usuario indicando el motivo. Queda en estado `PENDIENTE` para su revisión | Autenticado | `CrearReporteRequest`: `tipoObjetivo` (`PUBLICACION` \| `USUARIO`), `objetivoId`, `motivo` | `ReporteResponse` | `201`, `400`, `401`, `404`, `409` |

Valores de `motivo`: `CONTENIDO_INAPROPIADO`, `SPAM`, `INFORMACION_FALSA`, `ACOSO`,
`ANIMAL_EN_PELIGRO`, `OTRO`. **`409`** si el usuario ya había reportado ese mismo objetivo.

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
| `DELETE` | `/api/admin/publicaciones/{id}?tipo={tipo}` | Elimina cualquier publicación. `tipo` es obligatorio: `ALERTA`, `AVISTAMIENTO` o `ADOPCION`, porque el id solo es único dentro de su tabla | `ADMIN` | — | — | `204`, `400`, `401`, `403`, `404` |

---

## 12. `/api/notificaciones` — Notificaciones

RF-05.

| Método | Ruta | Descripción | Auth | Body de entrada | Respuesta | Códigos |
|---|---|---|---|---|---|---|
| `GET` | `/api/notificaciones` | Notificaciones del usuario, más recientes primero. Filtro `leida` | Autenticado | — | Página de `NotificacionResponse` | `200`, `401` |
| `PATCH` | `/api/notificaciones/{id}/leida` | Marca una notificación como leída | Propietario | — | — | `204`, `401`, `403`, `404` |
| `PATCH` | `/api/notificaciones/leidas` | Marca como leídas todas las del usuario | Autenticado | — | — | `204`, `401` |

Tipos de notificación: `ALERTA_CERCANA`, `NUEVO_MATCH`, `NUEVO_MENSAJE`, `PROTECTORA_VERIFICADA`,
`ADOPCION_CONFIRMADA`, `REPORTE_RESUELTO`.

---

## 13. Canales WebSocket (STOMP)

Conexión al endpoint STOMP con el JWT en el handshake (cabecera `Authorization` o parámetro
`token`). Un usuario solo puede suscribirse a canales propios; en caso contrario el servidor cierra
la suscripción.

| Canal | Dirección | Descripción | Auth | Payload |
|---|---|---|---|---|
| `/ws/chat/{id}` | Bidireccional | Mensajería en tiempo real de una conversación (RF-12) | Participante del chat | Envío: `EnviarMensajeRequest`. Recepción: `MensajeResponse` |
| `/ws/notificaciones` | Servidor → cliente | Notificaciones del usuario, incluidas las alertas registradas en su área geográfica (RF-05) | Autenticado | `NotificacionResponse` |
| `/ws/mapa` | Servidor → cliente | Altas, cambios de estado y cierres de alertas para refrescar el mapa sin recargar (RF-04). Debe soportar 100 usuarios simultáneos (RNF-02) | Autenticado | `AlertaMapaResponse` + `accion`: `ALTA` \| `ACTUALIZACION` \| `CIERRE` |

---

## 14. Trazabilidad requisito → endpoint

| RF | Dónde se cubre |
|---|---|
| RF-01 | `POST /api/auth/registro/ciudadano`, `POST /api/auth/registro/protectora` |
| RF-02 | `PUT /api/usuarios/me` |
| RF-03 | `POST /api/alertas` |
| RF-04 | `GET /api/alertas/mapa`, canal `/ws/mapa` |
| RF-05 | `GET /api/notificaciones`, canal `/ws/notificaciones` |
| RF-06 | `POST /api/protectoras/{id}/contactar` |
| RF-07 | `POST /api/adopcion` |
| RF-08 | `POST /api/matches` con `tipoPublicacion=AVISTAMIENTO` |
| RF-09 | `POST /api/matches` con `tipoPublicacion=ADOPCION` |
| RF-10 | `GET /api/protectoras/panel` |
| RF-11 | Todo `/api/admin` |
| RF-12 | `/api/chats/**`, canal `/ws/chat/{id}` |
| RF-13 | `POST /api/auth/login` |
| RF-14 | `PATCH /api/alertas/{id}/cerrar` |
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

## 15. Endpoints inferidos, pendientes de confirmación

El resumen de partida enumeraba los recursos y sus rutas principales. Estos endpoints se han
deducido para cubrir requisitos que, si no, se quedaban sin ruta. **Están sujetos a revisión** antes
de implementarse:

| Endpoint | Motivo |
|---|---|
| `POST /api/matches/descartar` | RF-15 (swipe de descarte) no tenía ruta en el resumen |
| `GET /api/adopcion/feed` | RF-09 + RF-15: el swipe de adopción necesita su propio feed, igual que el de avistamientos |
| `GET /api/admin/usuarios` | RF-11 dice «gestionar a todos los usuarios», lo que exige poder listarlos |
| `PATCH /api/admin/reportes/{id}/revisado` | El enum `EstadoReporte` tiene el valor `REVISADO`, que necesita una transición |
| `GET /api/usuarios/{id}` | El chat y el panel de protectora necesitan mostrar datos del interlocutor |
| `GET /api/avistamientos/{id}`, `GET /api/avistamientos?alertaId=` | Consultar el detalle y los avistamientos de una alerta |
| `POST /api/chats/{id}/mensajes`, `PATCH /api/chats/{id}/mensajes/leidos` | El campo `leido` de MENSAJE necesita una transición; el POST es la alternativa REST al WebSocket |
| `PATCH /api/notificaciones/leidas` | Comodidad de cliente; el resumen solo preveía marcar una |
| Parámetro `?tipo=` en `DELETE /api/admin/publicaciones/{id}` | Las publicaciones son de tres tablas distintas: el id solo no identifica el recurso |
