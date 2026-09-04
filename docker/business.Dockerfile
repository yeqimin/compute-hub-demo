FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY proto/pom.xml proto/pom.xml
COPY business-server/pom.xml business-server/pom.xml
COPY mock-engine/pom.xml mock-engine/pom.xml
COPY proto/src proto/src
COPY business-server/src business-server/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl business-server -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/business-server/target/business-server-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
