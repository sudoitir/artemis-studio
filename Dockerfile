# Artemis Studio — single image: React SPA baked into the Spring Boot jar.
# syntax=docker/dockerfile:1

# ── 1. Build the frontend ────────────────────────────────────────────────────
FROM node:26-bookworm-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ── 2. Build the jar (frontend copied in via the `frontend` profile is skipped;
#      we pass the already-built dist straight through instead) ───────────────
FROM maven:3.9-eclipse-temurin-25 AS app
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -e -B dependency:go-offline
COPY src/ ./src/
COPY --from=web /web/dist/ ./src/main/resources/static/
RUN mvn -q -B clean package -DskipTests

# ── 3. Runtime (Ubuntu 26.04 LTS "resolute") ─────────────────────────────────
FROM eclipse-temurin:25-jre-resolute AS runtime
RUN groupadd -r studio && useradd -r -g studio studio \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=app /src/target/artemis-studio.jar app.jar
USER studio
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
