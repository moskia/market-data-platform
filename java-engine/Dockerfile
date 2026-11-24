# One image for both Java programs: the engine and the simulator. The command picks which one runs.

# Stage 1: build with Maven. Dependencies are downloaded in their own layer, so changing only the
# code doesn't download them again.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests \
 && mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

# Stage 2: only what's needed to run (a JRE, no Maven, no source code).
FROM eclipse-temurin:21.0.8_9-jre
WORKDIR /app
COPY --from=build /build/target/lib ./lib
COPY --from=build /build/target/java-engine-*.jar ./app.jar

ENTRYPOINT ["java", "-cp", "/app/app.jar:/app/lib/*"]
CMD ["com.marketdata.app.MarketDataConsumerMain"]
