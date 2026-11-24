# Build from the repository root: docker build -t chess-engine-api .
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# chess-engine-api depends on engine-core, and Maven needs every module listed in the root pom.
COPY pom.xml pom.xml
COPY engine-core engine-core
COPY engine-uci engine-uci
COPY engine-tuner engine-tuner
COPY chess-engine-api chess-engine-api

# Tests run in CI; the image build only packages the executable JAR.
RUN mvn -B -pl chess-engine-api -am package -DskipTests


FROM eclipse-temurin:21-jre

WORKDIR /opt/app

COPY --from=builder /app/chess-engine-api/target/ce-api.jar run.jar
CMD ["java", "-jar", "/opt/app/run.jar"]
