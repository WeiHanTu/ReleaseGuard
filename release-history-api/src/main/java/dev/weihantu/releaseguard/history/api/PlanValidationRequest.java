package dev.weihantu.releaseguard.history.api;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import java.util.List;

public record PlanValidationRequest(ReleasePlan plan, List<MetricPolicy> policies) {

  public PlanValidationRequest {
    policies = policies == null ? List.of() : List.copyOf(policies);
  }
}
