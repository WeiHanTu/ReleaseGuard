package dev.weihantu.releaseguard.history.api;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import java.util.List;

public record PlanRenderRequest(
    ReleasePlan plan, List<MetricPolicy> policies, String uid, Long generation) {

  public PlanRenderRequest {
    policies = policies == null ? List.of() : List.copyOf(policies);
  }
}
