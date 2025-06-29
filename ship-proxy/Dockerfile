
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

COPY . .
RUN mvn clean package -DskipTests

FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=builder /app/target/ship-proxy-1.0-SNAPSHOT.jar app.jar


EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

CMD ["java", "-jar", "app.jar"]