FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency resolution as its own layer -- only re-download the world
# when pom.xml actually changes, not on every source edit.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/agentic-sheets-backend.jar /app/agentic-sheets-backend.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/agentic-sheets-backend.jar"]
