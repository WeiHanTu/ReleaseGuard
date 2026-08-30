package dev.weihantu.releaseguard.gate.prometheus;

import java.time.Instant;
import java.util.Map;

public record PrometheusObservation(
    String query, Instant observedAt, double value, Map<String, String> labels) {
  public PrometheusObservation {
    labels = Map.copyOf(labels);
  }
}
