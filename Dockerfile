# syntax=docker/dockerfile:1
# Cloud Run runs amd64; build with --platform linux/amd64 on an ARM machine.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY scripts/fetch-models.sh scripts/
RUN scripts/fetch-models.sh
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/radar-rag-0.0.1-SNAPSHOT.jar extract --destination app

FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home radar
WORKDIR /application
COPY --from=build /build/app ./
COPY --from=build /build/models models
# The training run below unpacks the tokenizer's native library here, so runtime writes nothing.
ENV DJL_CACHE_DIR=/application/djl \
    SERVER_ADDRESS=0.0.0.0
# The training run must use the entrypoint's flags: a different --enable-native-access silently drops
# the AOT cache (-XX:AOTMode=on turns that into an error).
# No machine code in the AOT cache: JDK 25 stores adapters and stubs compiled for the training host's CPU,
# and a host without those instructions dies with SIGILL at boot (seen on CI, image cached from another
# runner). Classes and profiles, the bulk of the cold-start win, stay CPU-neutral.
ENV JAVA_FLAGS="--enable-native-access=ALL-UNNAMED -Dai.djl.offline=true \
    -XX:+UnlockDiagnosticVMOptions -XX:-AOTAdapterCaching -XX:-AOTStubCaching"
# AOT-cache training run: refresh the context and exit, with no database contact. BuildKit with tracing
# on (GitHub's buildx) points RUN steps' OTEL_* trace variables at a unix socket; Boot maps them onto its
# own OTLP exporter, which rejects a non-http endpoint and fails the refresh. DJL unpacks into
# owner-only directories and the runtime user is not root.
RUN env -u OTEL_TRACES_EXPORTER -u OTEL_EXPORTER_OTLP_TRACES_ENDPOINT -u OTEL_EXPORTER_OTLP_TRACES_PROTOCOL \
      NOTION_TOKEN=training java $JAVA_FLAGS -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
      -jar radar-rag-0.0.1-SNAPSHOT.jar --spring.ai.vectorstore.pgvector.initialize-schema=false \
 && chmod -R a+rX djl
USER radar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_FLAGS -XX:AOTCache=app.aot -jar radar-rag-0.0.1-SNAPSHOT.jar"]
