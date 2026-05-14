# Stage 1: Build — immagine Maven ufficiale, non richiede mvnw né .mvn/
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /build

# Cache dipendenze Maven prima del sorgente
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build fast-jar senza test
COPY src src
RUN mvn package -DskipTests -q

# Stage 2: Runtime — minimal JRE
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build --chown=appuser:appgroup /build/target/quarkus-app/lib/       ./lib/
COPY --from=build --chown=appuser:appgroup /build/target/quarkus-app/*.jar      ./
COPY --from=build --chown=appuser:appgroup /build/target/quarkus-app/app/       ./app/
COPY --from=build --chown=appuser:appgroup /build/target/quarkus-app/quarkus/   ./quarkus/

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Dquarkus.http.host=0.0.0.0", \
  "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", \
  "-jar", "quarkus-run.jar"]
