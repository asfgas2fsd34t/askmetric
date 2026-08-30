FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY apps/server/pom.xml apps/server/pom.xml
RUN mvn -f apps/server/pom.xml dependency:go-offline -DskipTests
COPY apps/server/src apps/server/src
COPY contracts contracts
RUN mvn -f apps/server/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/apps/server/target/askmetric-server-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
