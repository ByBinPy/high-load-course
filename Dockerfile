FROM eclipse-temurin:17-alpine-3.22 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src src
RUN mvn package

COPY --from=build /app/target/*.jar /high-load-course.jar

CMD ["java", "-jar", "/high-load-course.jar"]

