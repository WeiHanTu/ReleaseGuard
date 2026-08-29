package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicyStatus;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class KubernetesReconciliationRuntimeTest {
  @Test
  void acceptsOnlyThePolicyVersionThatPassedDryRun() {
    MetricPolicy policy = policy("17", "17", true);
    assertTrue(KubernetesReconciliationRuntime.isAcceptedCurrentVersion(policy));

    policy.setStatus(new MetricPolicyStatus(true, "16", null, null, List.of()));
    assertFalse(KubernetesReconciliationRuntime.isAcceptedCurrentVersion(policy));

    policy.setStatus(new MetricPolicyStatus(false, "17", null, null, List.of()));
    assertFalse(KubernetesReconciliationRuntime.isAcceptedCurrentVersion(policy));
  }

  private static MetricPolicy policy(
      String resourceVersion, String observedResourceVersion, boolean accepted) {
    MetricPolicy policy = new MetricPolicy();
    policy.setMetadata(
        new ObjectMetaBuilder()
            .withName("http-errors")
            .withResourceVersion(resourceVersion)
            .build());
    policy.setSpec(OperatorTestFixtures.policy());
    policy.setStatus(
        new MetricPolicyStatus(accepted, observedResourceVersion, null, null, List.of()));
    return policy;
  }
}
