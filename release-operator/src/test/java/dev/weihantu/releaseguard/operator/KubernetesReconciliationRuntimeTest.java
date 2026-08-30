package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicyStatus;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KubernetesReconciliationRuntimeTest {
  @Test
  void acceptsOnlyThePolicyVersionThatPassedDryRun() {
    MetricPolicy policy = policy(17, 17, true);
    assertTrue(KubernetesReconciliationRuntime.isAcceptedCurrentVersion(policy));

    policy.setStatus(new MetricPolicyStatus(true, 16L, null, null, List.of()));
    assertFalse(KubernetesReconciliationRuntime.isAcceptedCurrentVersion(policy));

    policy.setStatus(new MetricPolicyStatus(false, 17L, null, null, List.of()));
    assertFalse(KubernetesReconciliationRuntime.isAcceptedCurrentVersion(policy));
  }

  @Test
  void retriesTransientCleanupEditConflicts() {
    AtomicInteger attempts = new AtomicInteger();

    KubernetesReconciliationRuntime.retryCleanupEdit(
        () -> {
          if (attempts.incrementAndGet() < 3) {
            throw new KubernetesClientException("conflict", 409, null);
          }
        });

    assertEquals(3, attempts.get());
  }

  @Test
  void treatsConcurrentCleanupDeletionAsSuccess() {
    KubernetesReconciliationRuntime.retryCleanupEdit(
        () -> {
          throw new KubernetesClientException("gone", 404, null);
        });
  }

  @Test
  void doesNotRetryNonConflictCleanupFailures() {
    AtomicInteger attempts = new AtomicInteger();

    assertThrows(
        KubernetesClientException.class,
        () ->
            KubernetesReconciliationRuntime.retryCleanupEdit(
                () -> {
                  attempts.incrementAndGet();
                  throw new KubernetesClientException("forbidden", 403, null);
                }));

    assertEquals(1, attempts.get());
  }

  @Test
  void boundsRepeatedCleanupConflicts() {
    AtomicInteger attempts = new AtomicInteger();

    assertThrows(
        KubernetesClientException.class,
        () ->
            KubernetesReconciliationRuntime.retryCleanupEdit(
                () -> {
                  attempts.incrementAndGet();
                  throw new KubernetesClientException("conflict", 409, null);
                }));

    assertEquals(5, attempts.get());
  }

  private static MetricPolicy policy(long generation, long observedGeneration, boolean accepted) {
    MetricPolicy policy = new MetricPolicy();
    policy.setMetadata(
        new ObjectMetaBuilder().withName("http-errors").withGeneration(generation).build());
    policy.setSpec(OperatorTestFixtures.policy());
    policy.setStatus(new MetricPolicyStatus(accepted, observedGeneration, null, null, List.of()));
    return policy;
  }
}
