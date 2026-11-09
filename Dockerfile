# =============================================================================
# Etapa 1 — compilacion
# Se copian primero el wrapper y el pom para que la descarga de dependencias
# quede en una capa cacheada y no se repita en cada cambio del codigo fuente.
# =============================================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# =============================================================================
# Etapa 2 — ejecucion
# Solo el JRE y el jar: imagen final pequena y sin herramientas de compilacion.
# =============================================================================
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Usuario sin privilegios: la aplicacion no necesita ser root. su-exec es para
# soltar root en la entrada (docker-entrypoint.sh), no para la aplicacion.
RUN apk add --no-cache su-exec && addgroup -S spring && adduser -S spring -G spring

COPY --from=build /build/target/*.jar app.jar
RUN chown spring:spring app.jar
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 755 /usr/local/bin/docker-entrypoint.sh

# Sin "USER spring": el contenedor arranca como root SOLO para preparar la
# carpeta de fotos del volumen, que Railway monta como root, y la entrada
# ejecuta java como "spring". Ver docker-entrypoint.sh.
EXPOSE 8080

# Esta imagen es la de produccion, y arranca con el perfil prod sin depender de
# que alguien lo ponga en las variables del servicio. Sin el, la aplicacion
# arrancaria con los valores de desarrollo de application.properties: secreto
# JWT de desarrollo, correo por SMTP, sin TLS hasta PostgreSQL y con la IP del
# limite de intentos sin proxy de confianza. Con el, cada secreto que falte
# impide arrancar (application-prod.properties). PerfilProdTest lo comprueba.
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["docker-entrypoint.sh"]
