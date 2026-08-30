FROM eclipse-temurin:21-jdk-jammy@sha256:ce5767b7222312d42395f5bab033cd91f09e44032a2f21bdfd7b5b912dbe1e77 AS build

WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY api-model/build.gradle.kts api-model/build.gradle.kts
COPY cli/build.gradle.kts cli/build.gradle.kts
COPY demo-service/build.gradle.kts demo-service/build.gradle.kts
COPY metric-gate-service/build.gradle.kts metric-gate-service/build.gradle.kts
COPY release-history-api/build.gradle.kts release-history-api/build.gradle.kts
COPY release-operator/build.gradle.kts release-operator/build.gradle.kts
RUN ./gradlew :release-operator:dependencies --configuration runtimeClasspath --no-daemon

COPY api-model/src/ api-model/src/
COPY release-operator/src/ release-operator/src/
RUN ./gradlew :release-operator:installDist --no-daemon

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:bb0b3c7edc4417acdf76ea0f52bb5fae28881fe05aae6cc55af4cc4cb0200d2d

ARG VCS_REF=unknown
LABEL org.opencontainers.image.title="ReleaseGuard operator" \
      org.opencontainers.image.description="Reconciles ReleasePlan resources into Argo Rollouts" \
      org.opencontainers.image.source="https://github.com/WeiHanTu/ReleaseGuard" \
      org.opencontainers.image.revision="${VCS_REF}"

WORKDIR /app
COPY --from=build --chown=65532:65532 /workspace/release-operator/build/install/release-operator/lib/ /app/lib/

USER 65532:65532
EXPOSE 8081

ENTRYPOINT ["/usr/bin/java"]
CMD ["-cp", "/app/lib/*", "dev.weihantu.releaseguard.operator.ReleaseGuardOperator"]
