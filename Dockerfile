FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src src
RUN mvn package

FROM eclipse-temurin:17-alpine-3.22

COPY --from=build /app/target/*.jar /high-load-course.jar

CMD ["java", "-jar", "/high-load-course.jar"]

