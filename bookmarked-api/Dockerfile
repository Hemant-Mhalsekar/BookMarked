# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S bookmarked && adduser -S bookmarked -G bookmarked
# Private e-book storage (mounted as a volume in docker-compose)
RUN mkdir -p /app/data/files && chown -R bookmarked:bookmarked /app/data
COPY --from=build /app/target/bookmarked-api-*.jar app.jar
USER bookmarked
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
# 65% leaves room for metaspace and threads on small (512 MB) hosts
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "app.jar"]
