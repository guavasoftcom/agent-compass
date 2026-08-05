# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Agent Compass — runtime image bundling the Vite frontend and the Spring Boot
# backend.
#
# This image performs NO build of its own: it packages artifacts that were
# already built — the executable jar (`./mvnw package`) and the frontend bundle
# (`yarn build`). That build lives in .github/workflows/release.yml.
#
# The frontend ships as plain files next to the jar rather than inside it, so
# the backend source tree stays free of build output; SPRING_WEB_RESOURCES_STATIC_LOCATIONS
# points Spring at them, and SinglePageApplicationConfig adds the history-mode
# fallback on top.
#
# Because the SPA is served by the backend itself, the browser talks to the API
# on its own origin: every fetch is a relative "/api/..." path, so no backend
# URL is baked into the bundle and nothing resolves to localhost when the image
# runs on another host.
# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre

# Prebuilt artifacts, relative to the build context.
ARG JAR_FILE=backend/target/*.jar
ARG FRONTEND_DIST=frontend/dist

RUN groupadd --system agentcompass \
    && useradd --system --gid agentcompass --home-dir /app --shell /usr/sbin/nologin agentcompass

WORKDIR /app

COPY --chown=agentcompass:agentcompass ${JAR_FILE} /app/agent-compass.jar
COPY --chown=agentcompass:agentcompass ${FRONTEND_DIST}/ /app/static/

USER agentcompass

EXPOSE 8080

# Overrides Boot's classpath defaults: the SPA lives on the filesystem here.
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/static/

# Postgres connection details must be supplied at run time (SPRING_DATASOURCE_URL
# / _USERNAME / _PASSWORD): application.yml falls back to localhost, which is not
# reachable from inside a container. They are deliberately not declared here —
# an empty ENV would win over that fallback and fail with a blank JDBC URL.
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/agent-compass.jar"]
