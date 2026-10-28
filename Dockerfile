# =============================================================================
# Content Recommendation Feedback Dashboard - multi-stage Docker image
#
#   Stage 1 (build)   : Maven + JDK 21, resolves dependencies (cached layer) and
#                       produces the runnable Spring Boot fat JAR.
#   Stage 2 (runtime) : JRE 21 only, non-root user, health check, no build tools.
#
# Build:  docker build -t crfd:1.0.0 .
# Run  :  docker run -d --name crfd -p 8080:8080 -e CRFD_ENVIRONMENT=docker crfd:1.0.0
# =============================================================================

# ------------------------------------------------------------------ stage 1: build
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Dependency layer: copied first so that source-only changes do not invalidate the
# (slow) dependency download layer.
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd ./
# Windows checkouts store mvnw as 100644, so the copy arrives without the exec
# bit and every ./mvnw line below dies with "Permission denied" (exit 126).
RUN chmod +x mvnw
RUN ./mvnw -B -q -DskipTests dependency:go-offline -Dmaven.wagon.http.retryHandler.count=3 || true

# Application layer
COPY src ./src
RUN ./mvnw -B -DskipTests clean package \
 && cp target/content-recommendation-feedback-dashboard.jar /workspace/app.jar

# ------------------------------------------------------------------ stage 2: runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL org.opencontainers.image.title="Content Recommendation Feedback Dashboard" \
      org.opencontainers.image.description="MVP: recommendation feedback events, searchable dashboard, summary indicators, status drill-down, alert/exception view" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="MIT"

ARG APP_VERSION=1.0.0-SNAPSHOT
ARG BUILD_REVISION=local

ENV APP_HOME=/app \
    SERVER_PORT=8080 \
    CRFD_ENVIRONMENT=docker \
    APP_VERSION=${APP_VERSION} \
    BUILD_REVISION=${BUILD_REVISION} \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Duser.timezone=UTC"

WORKDIR ${APP_HOME}

# curl is used by the HEALTHCHECK below; tzdata keeps log timestamps predictable.
RUN apk add --no-cache curl tzdata \
 && addgroup -S crfd \
 && adduser -S -G crfd crfd \
 && mkdir -p /app/crfd-data \
 && chown -R crfd:crfd /app

COPY --from=build --chown=crfd:crfd /workspace/app.jar /app/app.jar

USER crfd

EXPOSE 8080

# Event data lives on a volume so the container stays stateless and replaceable.
VOLUME ["/app/crfd-data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=50s --retries=5 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/api/v1/health" > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
