# One image: React UI is built and bundled into the Spring Boot jar (served from /static).

# ---- 1. build the React frontend ----
FROM node:20-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. build the Spring Boot backend ----
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /frontend/dist ./src/main/resources/static
RUN mvn -q -B package -DskipTests

# ---- 3. runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=backend /backend/target/speech-to-text.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
