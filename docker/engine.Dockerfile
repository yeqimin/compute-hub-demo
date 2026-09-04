FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY proto/pom.xml proto/pom.xml
COPY business-server/pom.xml business-server/pom.xml
COPY mock-engine/pom.xml mock-engine/pom.xml
COPY proto/src proto/src
COPY mock-engine/src mock-engine/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -pl mock-engine -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/mock-engine/target/mock-engine-1.0.0.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=60","-jar","/app/app.jar"]
