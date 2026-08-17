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

- **Con Docker:** Docker y Docker Compose. No hace falta nada más.
- **Sin Docker:** JDK 17 y un PostgreSQL 16 con la extensión PostGIS habilitada.

## Arranque en local con Docker Compose

1. Copia el fichero de variables de entorno y ajusta los valores:

   ```bash
   cp .env.example .env
   ```

   Como mínimo cambia `DB_PASSWORD` y `JWT_SECRET`. Para generar un secreto:

   ```bash
   openssl rand -base64 48
   ```

2. Levanta la API y la base de datos:

   ```bash
   docker compose up -d --build
   ```

   El servicio `app` espera a que PostgreSQL esté listo (`healthcheck` con `pg_isready`), así que el
   primer arranque tarda algo más.

3. Comprueba que responde:

   ```bash
   curl http://localhost:8080/actuator/health
   # {"status":"UP"}
   ```

4. Comandos útiles:

   ```bash
   docker compose logs -f app     # seguir los logs de la API
   docker compose restart app     # reiniciar solo la API
   docker compose down            # parar, conservando los datos
   docker compose down -v         # parar y BORRAR los datos de la base
   ```

La API queda en `http://localhost:8080` y la base de datos en `localhost:5432`.

## Arranque sin Docker

Con un PostgreSQL propio, crea la base de datos y habilita PostGIS:

```sql
CREATE DATABASE rescate_animal;
\c rescate_animal
CREATE EXTENSION IF NOT EXISTS postgis;
```

Luego arranca la aplicación:

```bash
./mvnw spring-boot:run          # Linux / macOS
.\mvnw.cmd spring-boot:run      # Windows
```

Los valores por defecto de `application.properties` apuntan a
`jdbc:postgresql://localhost:5432/rescate_animal` con usuario y contraseña `rescate`. Para cambiarlos
sin tocar el fichero, exporta las variables de entorno.

### Compilar y probar

```bash
./mvnw clean compile            # compilar
./mvnw test                     # ejecutar los tests
./mvnw clean package            # generar el jar en target/
```

## Variables de entorno

| Variable | Por defecto (desarrollo) | Descripción |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/rescate_animal` | URL JDBC de PostgreSQL |
| `DB_USER` | `rescate` | Usuario de la base de datos |
| `DB_PASSWORD` | `rescate` | Contraseña de la base de datos |
| `JPA_DDL_AUTO` | `update` | Estrategia de esquema de Hibernate |
| `JWT_SECRET` | valor de desarrollo | Secreto de firma del JWT. **Obligatorio en producción** |
| `JWT_EXPIRATION` | `86400000` | Validez del token en ms (24 h) |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | SMTP para el email de recuperación de contraseña |
| `SERVER_PORT` | `8080` | Puerto de la API |
| `SPRING_PROFILES_ACTIVE` | `default` | Usa `prod` para el perfil de producción |

En el perfil `prod` los secretos **no tienen valor por defecto**: si falta una variable, la
aplicación no arranca en lugar de usar una credencial de desarrollo.

Ningún secreto se versiona. El fichero `.env` está en `.gitignore`; `.env.example` es solo la
plantilla.

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

Fase de *scaffolding*: estructura de paquetes, entidades JPA, infraestructura Docker y contrato de la
API. La lógica de negocio y la autenticación JWT se implementan en la fase siguiente, endpoint por
endpoint sobre `docs/api-rest.md`.
