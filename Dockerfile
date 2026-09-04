# ---------- Etapa 1: build ----------
# Se compila dentro de la imagen para que el artefacto sea Java 21 real,
# independiente del JDK que tenga la maquina del desarrollador.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Las dependencias se resuelven en una capa aparte: mientras el pom.xml no cambie,
# Docker reutiliza esta capa y el build es mucho mas rapido.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Etapa 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Usuario sin privilegios: el proceso no necesita root.
RUN addgroup -S tenpo && adduser -S tenpo -G tenpo
COPY --from=build /build/target/*.jar app.jar
RUN chown tenpo:tenpo app.jar
USER tenpo

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
