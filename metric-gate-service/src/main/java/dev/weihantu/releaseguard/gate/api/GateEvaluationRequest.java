package dev.weihantu.releaseguard.gate.api;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import java.time.Instant;

public record GateEvaluationRequest(
    String releaseId,
    Integer step,
    Mode mode,
    String namespace,
    String releaseName,
    PolicySnapshot policy,
    String stableRevision,
    String canaryRevision,
    Thresholds thresholds,
    Instant evaluationTime) {

  public enum Mode {
    Bootstrap,
    Canary
  }

  public record PolicySnapshot(String name, String resourceVersion, MetricPolicySpec spec) {}

  public record Thresholds(
      Double minCanaryValue, Double maxCanaryValue, Double maxDeltaFromStable) {}
}
