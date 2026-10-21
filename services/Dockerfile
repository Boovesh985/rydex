# syntax=docker/dockerfile:1
# One image per Spring service (ADR 0023), from the repo root:
#   docker build -f services/Dockerfile --build-arg SERVICE=ride-service --build-arg PORT=8081 .
# The Maven build runs once on the build machine's own platform (jars are portable) and is shared by
# all four images; only the small runtime stage is built per platform (amd64, arm64).

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY libs libs
COPY services services
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q package -DskipTests -Djacoco.skip=true

# Spring Boot layers: dependencies change rarely, the application on every commit
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS layers
ARG SERVICE
WORKDIR /x
COPY --from=build /src/services/${SERVICE}/target/${SERVICE}-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination out

FROM eclipse-temurin:21-jre-noble
ARG SERVICE
ARG PORT
LABEL org.opencontainers.image.source="https://github.com/Boovesh985/rydex" \
      org.opencontainers.image.title="rydex-${SERVICE}" \
      org.opencontainers.image.licenses="MIT"
RUN groupadd --system --gid 10001 rydex \
    && useradd --system --uid 10001 --gid rydex --no-create-home --shell /usr/sbin/nologin rydex
WORKDIR /app
COPY --from=layers /x/out/dependencies/ ./
COPY --from=layers /x/out/spring-boot-loader/ ./
COPY --from=layers /x/out/snapshot-dependencies/ ./
COPY --from=layers /x/out/application/ ./
USER 10001:10001
ENV PORT=${PORT}
EXPOSE ${PORT}
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/actuator/health" > /dev/null || exit 1
# heap = 55% of the container's memory limit (768 MB: a 422 MB heap). The rest is for what the JVM keeps
# outside the heap, measured at about 285 MB under load (metaspace, symbols, code cache, threads; ADR 0023).
# One GC thread; die (and be restarted) rather than limp on.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]
