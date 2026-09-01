package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicyStatus;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.List;
import java.util.Map;
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

  @Test
  void projectsPauseReasonsAndStepAnalysisEvidenceFromRolloutStatus() {
    GenericKubernetesResource rollout =
        rollout(
            4L,
            Map.of(
                "observedGeneration",
                "4",
                "phase",
                "Paused",
                "currentStepIndex",
                1,
                "stableRS",
                "stable-hash",
                "currentPodHash",
                "candidate-hash",
                "replicas",
                10,
                "readyReplicas",
                9,
                "pauseConditions",
                List.of(
                    Map.of(
                        "reason",
                        RolloutObservation.INCONCLUSIVE_ANALYSIS_PAUSE,
                        "startTime",
                        "2026-08-29T12:00:00Z")),
                "canary",
                Map.of(
                    "currentStepAnalysisRunStatus",
                    Map.of("name", "checkout-candidate-1", "status", "Inconclusive"))));

    RolloutObservation observation =
        KubernetesReconciliationRuntime.rolloutObservation(
            rollout, false, RolloutObservation.Abort.NONE, RolloutObservation.StepAnalysis.NONE);

    assertTrue(observation.present());
    assertTrue(observation.statusCurrent());
    assertTrue(observation.pausedByInconclusiveGate());
    assertEquals(1, observation.currentStepIndex());
    assertEquals("checkout-candidate-1", observation.stepAnalysis().name());
    assertTrue(observation.stepAnalysis().decided());
    assertFalse(observation.stepAnalysis().successful());
    assertFalse(observation.stepAnalysis().failed());
  }

  @Test
  void unobservedRolloutGenerationsAndPartialStatusAreNotTreatedAsEvidence() {
    RolloutObservation stale =
        KubernetesReconciliationRuntime.rolloutObservation(
            rollout(5L, Map.of("observedGeneration", "4", "phase", "Progressing")),
            false,
            RolloutObservation.Abort.NONE,
            RolloutObservation.StepAnalysis.NONE);

    assertFalse(stale.statusCurrent());
    assertFalse(stale.pausedByInconclusiveGate());
    assertFalse(stale.stepAnalysis().decided());
    assertFalse(stale.promoted(5));
  }

  @Test
  void promotionRequiresTheFinalStepAndAPromotedStableRevision() {
    RolloutObservation promoted =
        KubernetesReconciliationRuntime.rolloutObservation(
            rollout(
                6L,
                Map.of(
                    "observedGeneration",
                    "6",
                    "phase",
                    "Healthy",
                    "currentStepIndex",
                    5,
                    "stableRS",
                    "candidate-hash",
                    "currentPodHash",
                    "candidate-hash")),
            true,
            RolloutObservation.Abort.NONE,
            RolloutObservation.StepAnalysis.NONE);

    assertTrue(promoted.promoted(5));
    assertFalse(promoted.promoted(6));
    assertTrue(promoted.bootstrapWorkloadReady());
  }

  @Test
  void rollbackIsCompleteOnlyWhenBothTrafficPathsAreObserved() {
    assertFalse(rollback(new RolloutObservation.Abort(true, true, true), false));
    assertFalse(rollback(new RolloutObservation.Abort(true, true, false), true));
    assertFalse(rollback(new RolloutObservation.Abort(true, false, true), true));
    assertTrue(rollback(new RolloutObservation.Abort(true, true, true), true));
  }

  @Test
  void bootstrapReadinessRequiresObservedStableEndpoints() {
    Map<String, Object> healthy =
        Map.of(
            "observedGeneration",
            "6",
            "phase",
            "Healthy",
            "stableRS",
            "candidate-hash",
            "currentPodHash",
            "candidate-hash");

    assertFalse(
        KubernetesReconciliationRuntime.rolloutObservation(
                rollout(6L, healthy),
                false,
                RolloutObservation.Abort.NONE,
                RolloutObservation.StepAnalysis.NONE)
            .bootstrapWorkloadReady());
    assertTrue(
        KubernetesReconciliationRuntime.rolloutObservation(
                rollout(6L, healthy),
                true,
                RolloutObservation.Abort.NONE,
                RolloutObservation.StepAnalysis.NONE)
            .bootstrapWorkloadReady());
  }

  private static boolean rollback(RolloutObservation.Abort abort, boolean stableTrafficReady) {
    return KubernetesReconciliationRuntime.rolloutObservation(
            rollout(
                7L,
                Map.of(
                    "observedGeneration",
                    "7",
                    "phase",
                    "Degraded",
                    "stableRS",
                    "stable-hash",
                    "currentPodHash",
                    "candidate-hash")),
            stableTrafficReady,
            abort,
            RolloutObservation.StepAnalysis.NONE)
        .rollbackComplete();
  }

  private static GenericKubernetesResource rollout(long generation, Map<String, Object> status) {
    GenericKubernetesResource rollout = new GenericKubernetesResource();
    rollout.setApiVersion("argoproj.io/v1alpha1");
    rollout.setKind("Rollout");
    rollout.setMetadata(
        new ObjectMetaBuilder()
            .withName("checkout")
            .withNamespace("demo")
            .withGeneration(generation)
            .build());
    rollout.setAdditionalProperty("status", status);
    return rollout;
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
