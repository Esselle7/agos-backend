# Stage 1: Build
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /build

# Cache Maven dependencies separately from source
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -q

# Build fast-jar (tests run separately in CI)
COPY src src
RUN ./mvnw package -DskipTests -q

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
