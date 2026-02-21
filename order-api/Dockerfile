FROM amazoncorretto:17-al2-jdk as builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY src src
RUN chmod +x ./gradlew && \
    sed -i 's/\r$//' ./gradlew
RUN ./gradlew bootJar --no-daemon

FROM amazoncorretto:17-al2-generic
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app1.jar

RUN mkdir -p /app/logs

ENV SPRING_PROFILES_ACTIVE=prod
ENV SERVER_PORT=8083

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app1.jar"]