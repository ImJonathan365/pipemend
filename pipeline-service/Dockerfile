FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Build scripts first: this layer stays cached while only sources change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon --version

COPY src/ src/
# bootJar does not depend on the test task, so nothing is being skipped here: tests run through
# ./gradlew test and in CI, never inside the image build (Testcontainers would need a daemon).
RUN ./gradlew --no-daemon clean bootJar && cp build/libs/*.jar /workspace/app.jar

# ----- runtime stage -----
FROM eclipse-temurin:25-jre AS runtime

# docs/04 section 10 asks to verify in Sprint 1 whether the runtime image has curl or wget.
# Installing curl explicitly removes the doubt and keeps the compose healthcheck simple.
# Non-root user per NFR-14.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin pipemend

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

# Uploaded source files live here and are never modified (AC-01.6); compose mounts a volume.
RUN mkdir -p /data/inbox && chown -R 10001:10001 /data

USER 10001
EXPOSE 8080

# NFR-10. The eclipse-temurin image does not read JAVA_OPTS on its own; the JVM always reads
# JAVA_TOOL_OPTIONS (docs/04 section 3.1). docker-compose overrides this value.
ENV JAVA_TOOL_OPTIONS="-Xmx512m"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
