package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record ReconciliationPlan(
    ReleasePlanStatus status, List<HasMetadata> desiredResources, Duration requeueAfter) {

  public ReconciliationPlan {
    Objects.requireNonNull(status, "status");
    desiredResources = List.copyOf(desiredResources);
  }

  public boolean hasDesiredResources() {
    return !desiredResources.isEmpty();
  }
}
