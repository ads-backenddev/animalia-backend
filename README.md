# Backend — Plataforma de rescate y localización animal

API REST del Proyecto Intermodular del ciclo de Desarrollo de Aplicaciones Multiplataforma (DAM).

La plataforma conecta a ciudadanos, protectoras y administradores para localizar mascotas perdidas en
España. Un usuario publica una alerta con la última ubicación conocida de su mascota; el resto de
usuarios la ven en un mapa en tiempo real, registran avistamientos y hacen *match* con un sistema de
deslizamiento tipo swipe. Cuando alguien encuentra al animal se abre un chat en tiempo real para
coordinar la recuperación. Las protectoras verificadas publican además su catálogo de animales en
adopción, con filtros por especie, tamaño y zona, y reciben valoraciones de los usuarios.

## Stack

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 17 |
| Framework | Spring Boot 4.0.7 (Maven) |
| Base de datos | PostgreSQL 16 + PostGIS 3.4 |
| Geolocalización | Hibernate Spatial + JTS (`org.locationtech.jts.geom.Point`) |
| Seguridad | Spring Security + JWT (stateless), contraseñas con BCrypt |
| Tiempo real | WebSockets con STOMP (mapa, chat, notificaciones) |
| Panel web | Thymeleaf + Bootstrap 5 |
| Infraestructura | Docker + Docker Compose |

## Arquitectura

Arquitectura por capas estricta, para poder escalar los componentes de forma independiente
(RNF-09):

```
controller/   Capa de aplicación: expone REST, valida la entrada y delega en el service
service/      Capa de negocio: aquí vive TODA la lógica
repository/   Capa de datos: interfaces Spring Data JPA, sin lógica
model/        Entidades JPA (+ model/enums/)
dto/          request/ para entrada, response/ para salida
security/     JwtService, JwtAuthFilter, UserDetailsServiceImpl
config/       SecurityConfig, WebSocketConfig, CorsConfig
exception/    GlobalExceptionHandler y excepciones de negocio
```

Dos reglas que no se rompen: **un controller nunca llama a un repository directamente** y **un
repository nunca contiene lógica de negocio**. Los controllers tampoco devuelven entidades JPA:
siempre un DTO de `dto/response/`.

El contrato completo de la API está en [`docs/api-rest.md`](docs/api-rest.md).

## Requisitos previos

- JDK 17.
- Docker y Docker Compose **para la base de datos**. Alternativamente, un PostgreSQL 16 propio con la
  extensión PostGIS habilitada.

## Arranque en local

El flujo de desarrollo es: **la base de datos en Docker, la aplicación desde IntelliJ**. El
`docker-compose.yml` levanta únicamente PostgreSQL; el `Dockerfile` se conserva solo para el
despliegue en Railway.

### 1. Levantar la base de datos

Copia la plantilla de variables de entorno y ajusta los valores:

```bash
cp .env.example .env
```

Como mínimo cambia `DB_USER` y `DB_PASSWORD`. Luego:

```bash
docker compose up -d
```

> `POSTGRES_USER` y `POSTGRES_PASSWORD` solo se aplican **al crear el volumen por primera vez**. Si
> cambias las credenciales después, hace falta `docker compose down -v` (que **borra los datos**) para
> que tengan efecto.

**Si ya tienes PostgreSQL instalado en tu máquina**, tendrá ocupado el 5432 y el contenedor no podrá
publicar ese puerto. Compruébalo con:

```powershell
netstat -ano | findstr :5432
Get-Service postgresql*
```

En ese caso pon `DB_PORT=5433` en el `.env` y usa el mismo puerto en la URL del paso siguiente. No
sirve conectarse al PostgreSQL nativo: no lleva PostGIS, y las columnas `geometry(Point,4326)` de
`Alerta` y `Avistamiento` fallarían al crear el esquema.

### 2. Configurar el perfil `local` de la aplicación

Spring Boot **no lee ficheros `.env`**: ese fichero solo lo consume Docker Compose. Las credenciales
de la aplicación van en `src/main/resources/application-local.properties`, que está en `.gitignore`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5433/rescate_animal
spring.datasource.username=<el mismo DB_USER del .env>
spring.datasource.password=<el mismo DB_PASSWORD del .env>
server.port=8081
```

Las credenciales **deben coincidir** con las del `.env`, porque son las que usó Docker al crear la
base de datos, y el puerto de la URL debe ser el mismo `DB_PORT`.

### 3. Arrancar la aplicación

En IntelliJ: *Run > Edit Configurations…* y en **Active profiles** escribe `local`. O por línea de
comandos:

```bash
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"   # Windows
./mvnw spring-boot:run -Dspring-boot.run.profiles=local         # Linux / macOS
```

Comprueba que responde:

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP"}
```

### 4. Correo de pruebas (opcional)

`docker compose up -d` levanta también **Mailpit**, un servidor SMTP de mentira que no entrega nada:
acepta todos los correos y los muestra en una bandeja web. Sirve para comprobar el enlace de
recuperación de contraseña (RF-17) sin escribir a una dirección real.

- SMTP: `localhost:1025` — es el `MAIL_PORT` del `.env` y el valor por defecto de la aplicación.
- Bandeja de entrada: <http://localhost:8025>

Sin él, la aplicación arranca igual; el envío falla en segundo plano y solo queda registrado en el
log, porque `EmailService` no propaga el error (si lo hiciera, el flujo de recuperación revelaría
qué correos están registrados).

### Comandos útiles de Docker

```bash
docker compose logs -f db      # seguir los logs de PostgreSQL
docker compose down            # parar, conservando los datos
docker compose down -v         # parar y BORRAR los datos de la base
```

## Arranque con un PostgreSQL propio, sin Docker

Crea la base de datos y habilita PostGIS:

```sql
CREATE DATABASE rescate_animal;
\c rescate_animal
CREATE EXTENSION IF NOT EXISTS postgis;
```

Luego apunta `application-local.properties` a esa instancia y arranca como en el paso 3.

Los valores por defecto de `application.properties` apuntan a
`jdbc:postgresql://localhost:5432/rescate_animal` con usuario y contraseña `rescate`. Para cambiarlos
sin tocar el fichero, exporta las variables de entorno o usa el perfil `local`.

### Compilar y probar

```bash
./mvnw clean compile            # compilar
./mvnw clean package            # generar el jar en target/
```

Los tests necesitan la base de datos levantada y el perfil `local`, porque dos de ellos arrancan el
contexto de Spring contra PostGIS: `BackendApplicationTests` y `AlertaMapaTiempoRealTest`. Este
último es la prueba de extremo a extremo del mapa —publica una alerta, la recibe por el WebSocket,
comprueba el `409` del duplicado y la cierra— y la detección de duplicados se apoya en `ST_DWithin`,
que solo existe en PostGIS:

```bash
.\mvnw.cmd test "-DargLine=-Dspring.profiles.active=local"    # Windows
./mvnw test -DargLine=-Dspring.profiles.active=local          # Linux / macOS
```

El resto de pruebas (services, controllers con MockMvc, seguridad) no tocan la base de datos y se
pueden ejecutar sueltas con `-Dtest=NombreDelTest`.

## Variables de entorno

| Variable | Por defecto (desarrollo) | Descripción |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/rescate_animal` | URL JDBC de PostgreSQL |
| `DB_USER` | `rescate` | Usuario de la base de datos |
| `DB_PASSWORD` | `rescate` | Contraseña de la base de datos |
| `JPA_DDL_AUTO` | `validate` | Siempre `validate`: el esquema lo crea y lo cambia Flyway (`src/main/resources/db/migration`), y Hibernate solo comprueba que las entidades coinciden con las tablas. Con `update` taparía una migración olvidada |
| `JPA_LOCK_TIMEOUT_MS` | `5000` | Espera máxima, en milisegundos, de un bloqueo de fila sobre una alerta. Al vencer, el cliente recibe `409 RECURSO_OCUPADO` en lugar de quedarse esperando |
| `JWT_SECRET` | valor de desarrollo | Secreto de firma del JWT. **Obligatorio en producción** |
| `JWT_EXPIRATION` | `86400000` | Validez del token en ms (24 h) |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | SMTP para el email de recuperación de contraseña |
| `SERVER_PORT` | `8080` | Puerto de la API |
| `SPRING_PROFILES_ACTIVE` | `default` | Usa `prod` para el perfil de producción |
| `FOTOS_DIR` | `./uploads` | Carpeta de las fotos subidas. **Obligatoria en producción**: el punto de montaje del volumen |
| `APP_FOTOS_URL_PUBLICA` | `http://localhost:8080/fotos` | Base de las URLs de las fotos. **Obligatoria y `https` en producción** |
| `FOTOS_LIMITE_DIARIO` | `25` | Subidas de fotos por usuario y día |
| `FOTOS_LIMPIEZA_CRON` | `0 30 4 * * *` | Hora de la limpieza diaria de fotos sin uso (Europe/Madrid); `-` la desactiva |

En el perfil `prod` los secretos **no tienen valor por defecto**: si falta una variable, la
aplicación no arranca en lugar de usar una credencial de desarrollo.

Ningún secreto se versiona. Están en `.gitignore` tanto `.env` (que lee Docker Compose) como
`src/main/resources/application-local.properties` (que lee la aplicación). El único fichero de
configuración versionado con credenciales es `.env.example`, y solo lleva marcadores de posición.

| Fichero | Lo lee | ¿Se versiona? |
|---|---|---|
| `.env` | Docker Compose, para crear la base de datos | No |
| `.env.example` | Nadie: es la plantilla de `.env` | Sí, sin credenciales reales |
| `application-local.properties` | Spring Boot con el perfil `local` | No |

## Despliegue en Railway: el volumen de fotos

Railway construye la imagen con el `Dockerfile`. Las fotos que suben los usuarios se guardan en disco,
y el disco de un contenedor se pierde en cada despliegue: **sin un volumen, cada despliegue borra todas
las fotos**. Para configurarlo, en el servicio de la aplicación:

1. **Añadir un volumen** con punto de montaje `/data`.
2. **Variables**: `FOTOS_DIR=/data/fotos` y `APP_FOTOS_URL_PUBLICA=https://<dominio público del
   servicio>/fotos`. El dominio tiene que ser estable: las URLs se guardan completas, y si cambia, las
   fotos ya publicadas dejan de cargar.
3. **Una sola réplica.** Un volumen no se comparte entre instancias.
4. **No hace falta `RAILWAY_RUN_UID=0`.** Railway monta el volumen como root, y la imagen ya lo
   resuelve: `docker-entrypoint.sh` crea `/data/fotos`, se la da al usuario `spring` y arranca la
   aplicación como `spring`, nunca como root. `RAILWAY_RUN_UID=0` la ejecutaría entera como root.

Comprobación tras desplegar: el log de arranque debe decir `Fotos en /data/fotos`. Si en su lugar la
aplicación no arranca con `No se pudo crear la carpeta de fotos` o `La carpeta de fotos no admite
escritura`, el volumen no está montado donde dice `FOTOS_DIR` o no tiene permisos. Falla al
arrancar a propósito, para no descubrirlo con la primera subida de un usuario.

Esto se ha probado en local reproduciendo lo que hace Railway: la imagen en perfil `prod` con un
volumen de Docker montado como root en `/data`. La imagen anterior no arrancaba (`AccessDeniedException`
al crear `/data/fotos`); la actual arranca, sirve las fotos, las conserva al volver a crear el
contenedor y se para limpiamente.

## Convención de commits

[Conventional Commits](https://www.conventionalcommits.org/):

```
<tipo>: <descripción en imperativo y minúscula>
```

| Tipo | Cuándo |
|---|---|
| `feat` | Nueva funcionalidad |
| `fix` | Corrección de un error |
| `docs` | Solo documentación |
| `refactor` | Cambio interno que no altera el comportamiento |
| `test` | Añadir o modificar pruebas |

Ejemplos:

```
feat: añadir endpoint de publicación de alertas
fix: corregir el cálculo del radio de duplicados
docs: documentar los canales WebSocket
```

## Modelo de ramas — GitFlow

| Rama | Propósito |
|---|---|
| `main` | Código estable listo para producción. Solo recibe merges de `release/` y `hotfix/` |
| `develop` | Rama de integración del trabajo en curso |
| `feature/<nombre>` | Una rama por funcionalidad, siempre a partir de `develop` |
| `release/<version>` | Preparación de una versión |
| `hotfix/<nombre>` | Corrección urgente sobre `main` |

Nunca se commitea directamente a `main` ni a `develop`: todo cambio pasa por una rama `feature/`.

```bash
git switch develop
git switch -c feature/publicar-alertas
# ... trabajo y commits ...
git switch develop
git merge --no-ff feature/publicar-alertas
```

## Documentación del proyecto

| Documento | Contenido |
|---|---|
| [`docs/api-rest.md`](docs/api-rest.md) | Contrato completo de la API: endpoints, roles, bodies, respuestas y códigos HTTP |
| [`CLAUDE.md`](CLAUDE.md) | Contexto, arquitectura y convenciones de código del proyecto |

## Estado actual

El backend está construido salvo lo que señala la columna de estado. Quedan tres piezas sin construir:
RF-05 (notificaciones y *push*), los dos endpoints de contacto de RF-06 y `GET /api/avistamientos/{id}`.
Las tres están descritas en `docs/api-rest.md`, §20 («El SRS no queda cubierto al cien por cien» y
«Trabajo futuro»); la tercera es un pendiente aceptado, porque el detalle de una alerta ya devuelve sus
avistamientos.

| Bloque | Estado |
|---|---|
| Estructura por capas, 15 entidades JPA, esquema versionado con Flyway (`V1`–`V16`), `JPA_DDL_AUTO=validate`, Docker, contrato en `docs/api-rest.md` | Terminado |
| Autenticación y seguridad: JWT con revocación, registro de ciudadano y de protectora, login, logout y recuperación de contraseña (RF-01, RF-13, RF-16, RF-17) | Terminado |
| Perfil, idioma y baja de cuenta con anonimización (RF-02, RF-18, RF-19) | Terminado |
| Alertas: publicación, listado y detalle, edición, cierre, retirada por el dueño y moderación, mapa en tiempo real por STOMP, detección de duplicados y bloqueo de fila contra operaciones simultáneas (RF-03, RF-04, RF-11, RF-14, RF-25; §22) | Terminado |
| Avistamientos y su feed de swipe (RF-21) | Terminado, salvo `GET /api/avistamientos/{id}`, pendiente aceptado (§5, §20) |
| Matches, descarte y límite diario (RF-08, RF-09, RF-15, RF-24) | Terminado |
| Chat en tiempo real (RF-12) | Terminado |
| Adopción: publicación, catálogo con filtros, feed y confirmación (RF-07, RF-20, RF-22) | Terminado |
| Protectoras: directorio, ficha, valoraciones y panel (RF-06, RF-10, RF-23) | Terminado, **salvo los dos endpoints de contacto** de RF-06 (§9) |
| Reportes y administración (RF-11, RF-26) | Terminado |
| Subida de fotos: almacenamiento propio, borrado en la baja y al moderar una alerta, limpieza diaria y cupo (§21, §22) | Terminado |
| Notificaciones (RF-05) | **Solo persistencia**: no hay `GET /api/notificaciones` ni entrega en vivo ni *push*, que se diseñará con la app Android delante (§12, §20) |
