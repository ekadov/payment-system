FROM gradle:8.5-jdk21-alpine AS build
WORKDIR /app

COPY individuals-api/gradlew gradlew
COPY individuals-api/gradle gradle
COPY individuals-api/build.gradle.kts individuals-api/settings.gradle.kts individuals-api/gradle.properties ./
COPY individuals-api/openapi openapi
COPY individuals-api/src src
COPY infrastructure/local-repo /infrastructure/local-repo

RUN chmod +x gradlew && ./gradlew clean bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=build /app/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
