FROM maven:3.9.9-eclipse-temurin-17-alpine AS builder
LABEL authors="yashk"

WORKDIR /app

COPY src/ src/
COPY pom.xml pom.xml

RUN mvn clean package


FROM eclipse-temurin:17

WORKDIR /opt/app

COPY --from=builder /app/target/ce-api.jar run.jar
CMD ["java", "-jar", "/opt/app/run.jar"]