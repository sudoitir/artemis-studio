# syntax=docker/dockerfile:1
# Artemis Studio — single image: React SPA baked into the Spring Boot jar.
#
# The SPA and the jar are the same bytes on every architecture, so both build stages run on the
# runner's own platform ($BUILDPLATFORM); only the runtime stage is per-target. Building them
# under QEMU for arm64 made the release image take ten times longer for no different output.

# ── 1. Build the frontend ────────────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM node:24-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ── 2. Build the jar (frontend copied in via the `frontend` profile is skipped;
#      we pass the already-built dist straight through instead) ───────────────
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS app
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -e -B dependency:go-offline
COPY src/ ./src/
COPY --from=web /web/dist/ ./src/main/resources/static/
RUN mvn -q -B clean package -DskipTests

# ── 3. Runtime (Ubuntu 26.04 LTS "resolute") ─────────────────────────────────
FROM eclipse-temurin:25-jre-resolute@sha256:b8e5a7403fd1e1fd8cd09118f8a808ac0482736bef2946e89f261efbe71c52d8 AS runtime
# The distribution's security updates are applied at build time: a fix often reaches the Ubuntu
# archive days before a new base image does, and the image scan fails on a fixable high finding.
RUN groupadd -r studio && useradd -r -g studio studio \
    && apt-get update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=app /src/target/artemis-studio-exec.jar app.jar
USER studio
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
