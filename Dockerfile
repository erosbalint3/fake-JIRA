# syntax=docker/dockerfile:1

# ---- 1. Build the React frontend ----
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. Build the Spring Boot jar with the frontend bundled in ----
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /app
COPY backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/fake-jira-*.jar /app/app.jar

# ---- 3. Runtime image ----
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /data && chown app:app /data
WORKDIR /app
COPY --from=backend /app/app.jar ./app.jar
USER app

ENV SPRING_DATASOURCE_URL=jdbc:h2:file:/data/fakejira \
    APP_STORAGE_DIR=/data/attachments \
    APP_BACKUP_DIR=/data/backups \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8080/ || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
