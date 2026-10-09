# syntax=docker/dockerfile:1
# Build stage: the Maven Wrapper on JDK 25 (no local Java or Maven needed).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests -DskipITs -Dspotless.check.skip=true package \
 && cp target/*.jar /app.jar

# Runtime stage: a JRE 25 and the jar only.
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin shortener \
 && mkdir -p /data && chown shortener:shortener /data
COPY --from=build /app.jar /app/app.jar
USER shortener
WORKDIR /data
ENV DATABASE_PATH=/data/links.db
VOLUME /data
EXPOSE 8000 8081
# Readiness on the management port (ADR 0015); bash only, since the JRE image has no curl.
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${MANAGEMENT_PORT:-8081} && printf "GET /actuator/health/readiness HTTP/1.0\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200"'
# Exec form: the JVM is PID 1 and receives SIGTERM.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
