FROM gradle:8.8-jdk17 AS build
WORKDIR /home/gradle/project
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle clean build -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/gateway-all.jar /app/gateway.jar
RUN mkdir -p /app/web
EXPOSE 8080
ENV API_BACKEND_URL=http://backend:5000
CMD ["java", "-jar", "/app/gateway.jar"]
