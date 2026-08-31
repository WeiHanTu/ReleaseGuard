package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import java.util.Objects;

public record MetricPolicySnapshot(String resourceVersion, MetricPolicySpec spec) {
  public MetricPolicySnapshot {
    if (resourceVersion == null || resourceVersion.isBlank()) {
      throw new IllegalArgumentException("resourceVersion must not be blank");
    }
    Objects.requireNonNull(spec, "spec");
  }
}
