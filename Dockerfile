FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src src
RUN mvn package

FROM openjdk:17-jdk-slim-bullseye

COPY --from=build /app/target/*.jar /high-load-course.jar

CMD ["java", "-jar", "/high-load-course.jar"]
