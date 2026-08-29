package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicyStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class KubernetesReconciliationRuntime implements ReconciliationRuntime {
  @Override
  public Map<String, MetricPolicySpec> acceptedPolicies(
      ReleasePlan plan, Context<ReleasePlan> context) {
    Set<String> references = new LinkedHashSet<>();
    if (plan.getSpec() != null && plan.getSpec().rollout() != null) {
      List<ReleasePlanSpec.Gate> gates = plan.getSpec().rollout().gates();
      if (gates != null) {
        gates.stream()
            .filter(Objects::nonNull)
            .map(ReleasePlanSpec.Gate::policyRef)
            .filter(Objects::nonNull)
            .forEach(references::add);
      }
    }

    Map<String, MetricPolicySpec> accepted = new LinkedHashMap<>();
    for (String reference : references) {
      MetricPolicy policy =
          context.getClient().resources(MetricPolicy.class).withName(reference).get();
      if (isAcceptedCurrentVersion(policy)) {
        accepted.put(reference, policy.getSpec());
      }
    }
    return Map.copyOf(accepted);
  }

  @Override
  public void apply(List<HasMetadata> resources, Context<ReleasePlan> context) {
    for (HasMetadata resource : resources) {
      context
          .getClient()
          .resource(resource)
          .fieldManager(ReleaseResourceRenderer.FIELD_MANAGER)
          .serverSideApply();
    }
  }

  static boolean isAcceptedCurrentVersion(MetricPolicy policy) {
    if (policy == null || policy.getMetadata() == null) {
      return false;
    }
    MetricPolicyStatus status = policy.getStatus();
    return status != null
        && Boolean.TRUE.equals(status.accepted())
        && Objects.equals(
            policy.getMetadata().getResourceVersion(), status.observedResourceVersion());
  }
}
