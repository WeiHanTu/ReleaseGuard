package dev.weihantu.releaseguard.gate.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record GateEvaluationResponse(
    Decision decision,
    String reason,
    Double stableValue,
    Double canaryValue,
    Double stableSamples,
    Double canarySamples,
    Instant windowStart,
    Instant windowEnd,
    String policyResourceVersion,
    String evidenceDigest,
    List<Observation> observations) {

  public GateEvaluationResponse {
    observations = List.copyOf(observations);
  }

  public enum Decision {
    Pass,
    Fail,
    Inconclusive
  }

  public record Observation(
      String revisionRole,
      String measurement,
      String query,
      Instant observedAt,
      Double value,
      Map<String, String> labels,
      String outcome) {
    public Observation {
      labels = Map.copyOf(labels);
    }
  }
}
