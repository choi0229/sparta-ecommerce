FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY settings.gradle .
COPY build.gradle .
COPY src src
RUN gradle bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/libs/address-api-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
