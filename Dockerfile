
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /app/target/paybridge-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

# Tuned for small instances (e.g. Render free: 0.1 CPU, 512 MB):
#   SerialGC + C1-only JIT start much faster on a fraction of a CPU, and the heap may use 75% of
#   the container instead of the default 25% (128 MB is too tight once the OFAC list is loaded).
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=75 -Xss512k"

ENTRYPOINT ["java", "-jar", "app.jar"]

