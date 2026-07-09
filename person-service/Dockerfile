FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

COPY person-service/gradlew gradlew
COPY person-service/gradle gradle
COPY person-service/build.gradle.kts person-service/settings.gradle.kts person-service/gradle.properties ./
COPY person-service/client client
COPY person-service/openapi openapi
COPY person-service/src src

RUN chmod +x gradlew && ./gradlew clean bootJar -x test --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

COPY --from=build /app/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8082

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
