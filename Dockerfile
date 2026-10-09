# syntax=docker/dockerfile:1
# Cloud Run runs amd64; build with --platform linux/amd64 on an ARM machine.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# DJL 0.36.0's PyTorch. The native jars are only unpacked here (WarmDjl); the image carries the
# unpacked libraries, not a second copy inside the jars.
ARG PYTORCH_VERSION=2.7.1
ARG DJL_VERSION=0.36.0
ENV DJL_CACHE_DIR=/build/djl
COPY scripts/fetch-models.sh scripts/
RUN scripts/fetch-models.sh
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && mvn -B -q dependency:copy -DoutputDirectory=/natives \
      -Dartifact=ai.djl.pytorch:pytorch-native-cpu:${PYTORCH_VERSION}:jar:linux-x86_64 \
 && mvn -B -q dependency:copy -DoutputDirectory=/natives \
      -Dartifact=ai.djl.pytorch:pytorch-jni:${PYTORCH_VERSION}-${DJL_VERSION} \
 && java -Djarmode=tools -jar target/radar-rag-0.0.1-SNAPSHOT.jar extract --destination app
COPY docker/WarmDjl.java docker/
# Unpacked from the jar, the libtorch directory is named after its build date, which DJL never looks
# for once the jar is gone, so the runtime reaches it through PYTORCH_LIBRARY_PATH instead. DJL unpacks
# into owner-only directories and the runtime user is not root.
RUN java -Dai.djl.offline=true -cp "app/lib/*:/natives/*" docker/WarmDjl.java \
 && mv djl/pytorch/${PYTORCH_VERSION}-*-cpu-linux-x86_64 libtorch \
 && chmod -R a+rX djl libtorch

FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home radar
WORKDIR /application
COPY --from=build /build/app ./
COPY --from=build /build/models models
COPY --from=build /build/djl djl
COPY --from=build /build/libtorch libtorch
ENV DJL_CACHE_DIR=/application/djl \
    PYTORCH_LIBRARY_PATH=/application/libtorch \
    SERVER_ADDRESS=0.0.0.0
# MaxRAMPercentage 55: the model is read as one ~470 MB on-heap byte[] before ONNX Runtime copies it
# natively, so the default 25% heap on a 2 GiB container dies at boot. The training run must use the
# entrypoint's flags: a different --enable-native-access silently drops the AOT cache
# (-XX:AOTMode=on turns that into an error).
# No machine code in the AOT cache: JDK 25 stores adapters and stubs compiled for the training host's CPU,
# and a host without those instructions dies with SIGILL at boot (seen on CI, image cached from another
# runner). Classes and profiles, the bulk of the cold-start win, stay CPU-neutral.
ENV JAVA_FLAGS="-XX:MaxRAMPercentage=55 --enable-native-access=ALL-UNNAMED -Dai.djl.offline=true \
    -XX:+UnlockDiagnosticVMOptions -XX:-AOTAdapterCaching -XX:-AOTStubCaching"
# AOT-cache training run: refresh the context and exit, with no database contact. BuildKit with tracing
# on (GitHub's buildx) points RUN steps' OTEL_* trace variables at a unix socket; Boot maps them onto its
# own OTLP exporter, which rejects a non-http endpoint and fails the refresh.
RUN env -u OTEL_TRACES_EXPORTER -u OTEL_EXPORTER_OTLP_TRACES_ENDPOINT -u OTEL_EXPORTER_OTLP_TRACES_PROTOCOL \
      NOTION_TOKEN=training java $JAVA_FLAGS -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
      -jar radar-rag-0.0.1-SNAPSHOT.jar --spring.ai.vectorstore.pgvector.initialize-schema=false
USER radar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_FLAGS -XX:AOTCache=app.aot -jar radar-rag-0.0.1-SNAPSHOT.jar"]
