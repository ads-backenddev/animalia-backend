# Backend — Plataforma de rescate animal (Proyecto Intermodular DAM)

## Contexto
API REST para una plataforma de rescate y localización animal en
España. Gestiona alertas de mascotas perdidas con geolocalización en
tiempo real, sistema de match tipo swipe, chat, y catálogo de adopción
con protectoras verificadas.

## Stack
- Java 17 + Spring Boot 4.0.7 (Maven)
- PostgreSQL + PostGIS (Hibernate Spatial para tipos Point)
- Spring Security + JWT (stateless, sin sesiones)
- WebSockets con STOMP (mapa en tiempo real, chat, notificaciones)
- Thymeleaf + Bootstrap 5 (panel web de protectora/administrador)
- Docker + Docker Compose para desarrollo local

## Arquitectura — por capas, estricta
controller/  -> capa de aplicación (REST, valida entrada, delega a service)
service/     -> capa de negocio (TODA la lógica vive aquí)
repository/  -> capa de datos (interfaces Spring Data JPA, sin lógica)
model/       -> entidades JPA

Regla dura: un controller NUNCA llama a un repository directamente.
Un repository NUNCA contiene lógica de negocio.

## Convenciones de código
- Lombok para getters/setters (@Data o @Getter/@Setter), nunca manual.
- DTOs de entrada en dto/request/, de salida en dto/response/. Los
  controllers NUNCA devuelven entidades JPA directamente.
- Toda excepción de negocio extiende de una RuntimeException gestionada
  en exception/GlobalExceptionHandler.
- Los endpoints protegidos por rol usan @PreAuthorize, nunca
  comprobación manual del rol dentro del método.
- Los campos de geolocalización usan org.locationtech.jts.geom.Point
  (Hibernate Spatial), nunca latitud/longitud como campos separados.

## Git y commits
- GitFlow: main / develop / feature/<nombre> / release/ / hotfix/
- Conventional Commits: feat:, fix:, docs:, refactor:, test:
- Nunca commitear directamente a main o develop sin pasar por una rama
  feature/.

## Referencia de diseño
- docs/api-rest.md — contrato completo de endpoints (lo generarás en
  la Tarea 4)
- Este mismo CLAUDE.md contiene el resumen de requisitos y el modelo
  de datos completo — considéralo la fuente de verdad.

## Lo que NO debes hacer sin permiso explícito
- No renombrar entidades, campos o endpoints ya definidos aquí sin
  confirmarlo conmigo primero.
- No añadir dependencias nuevas al pom.xml sin justificarlo.
- No hacer push a un repositorio remoto.
- No implementar lógica de negocio real en esta primera fase de
  scaffolding (ver Tarea 2) — solo estructura.
