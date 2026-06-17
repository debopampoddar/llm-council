# syntax=docker/dockerfile:1.7

# The Maven build only produces a platform-neutral Spring Boot jar, but it still
# runs a JVM. Run the build on Docker's executable build platform and disable
# tiered compilation for Maven so Java 25 avoids the C1 compiler crash observed
# when Docker uses amd64 emulation on Apple Silicon.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

ARG MAVEN_OPTS="-XX:-TieredCompilation -XX:+UseSerialGC"
ENV MAVEN_OPTS=$MAVEN_OPTS

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# The runtime image must match the service platform requested by Compose.
FROM --platform=$TARGETPLATFORM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /workspace/target/llm-council-2.0.0.jar /app/llm-council.jar

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/llm-council.jar"]
