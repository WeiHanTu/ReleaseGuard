package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleasePlanPlannerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);

  private final ReleasePlanPlanner planner =
      new ReleasePlanPlanner(
          new ReleasePlanValidator(),
          new ReleaseResourceRenderer(),
          new ReleasePhaseMachine(),
          CLOCK);

  @Test
  void recordsValidationBeforeRenderingBootstrapResources() {
    var plan = OperatorTestFixtures.plan(1);

    ReconciliationPlan validating = planner.plan(plan, OperatorTestFixtures.policies());

    assertEquals(Phase.Validating, validating.status().phase());
    assertEquals(1, validating.status().observedGeneration());
    assertEquals(OperatorTestFixtures.UID + ":1", validating.status().releaseId());
    assertFalse(validating.hasDesiredResources());
    assertNotNull(validating.requeueAfter());

    plan.setStatus(validating.status());
    ReconciliationPlan bootstrapping = planner.plan(plan, OperatorTestFixtures.policies());

    assertEquals(Phase.Bootstrapping, bootstrapping.status().phase());
    assertEquals("checkout-0123456789ab", bootstrapping.status().canaryRevision());
    assertEquals(
        new ReleasePlanStatus.ResourceReference("checkout"), bootstrapping.status().rolloutRef());
    assertEquals(4, bootstrapping.desiredResources().size());
    assertNull(bootstrapping.requeueAfter());
  }

  @Test
  void duplicateReconciliationProducesTheSameDesiredStateAndStatus() {
    var plan = OperatorTestFixtures.plan(3);
    plan.setStatus(validatingStatus(3, null));
    ReconciliationPlan first = planner.plan(plan, OperatorTestFixtures.policies());

    plan.setStatus(first.status());
    ReconciliationPlan observedMissing = planner.plan(plan, OperatorTestFixtures.policies());
    plan.setStatus(observedMissing.status());
    ReconciliationPlan duplicate = planner.plan(plan, OperatorTestFixtures.policies());

    assertEquals(observedMissing.status(), duplicate.status());
    assertEquals(observedMissing.desiredResources(), duplicate.desiredResources());
  }

  @Test
  void anExistingStableRevisionStartsProgressiveDelivery() {
    var plan = OperatorTestFixtures.plan(4);
    plan.setStatus(validatingStatus(4, "checkout-previous"));

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies());

    assertEquals(Phase.Progressing, result.status().phase());
    assertEquals("checkout-previous", result.status().stableRevision());
    assertEquals(4, result.desiredResources().size());
  }

  @Test
  void missingAcceptedPolicyFailsWithoutCreatingChildren() {
    var plan = OperatorTestFixtures.plan(5);
    plan.setStatus(validatingStatus(5, null));

    ReconciliationPlan result = planner.plan(plan, Map.of());

    assertEquals(Phase.Failed, result.status().phase());
    assertFalse(result.hasDesiredResources());
    assertTrue(
        result.status().conditions().stream()
            .anyMatch(
                condition ->
                    condition.getType().equals("Degraded")
                        && condition.getStatus().equals("True")
                        && condition.getReason().equals("RG-PLAN-007")));
  }

  @Test
  void newerGenerationDuringActiveReleaseFailsClosed() {
    var plan = OperatorTestFixtures.plan(6);
    plan.setStatus(
        new ReleasePlanStatus(
            5L,
            OperatorTestFixtures.UID + ":5",
            Phase.Progressing,
            1,
            "checkout-previous",
            "checkout-current",
            new ReleasePlanStatus.ResourceReference("checkout"),
            null,
            null,
            null,
            List.of()));

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies());

    assertEquals(Phase.Failed, result.status().phase());
    assertEquals(6, result.status().observedGeneration());
    assertFalse(result.hasDesiredResources());
    assertTrue(
        result.status().conditions().stream()
            .anyMatch(condition -> condition.getReason().equals("RG-PLAN-011")));
  }

  @Test
  void projectsCurrentArgoPauseAndRevisionHashesIntoReleaseStatus() {
    var plan = OperatorTestFixtures.plan(7);
    plan.setStatus(activeStatus(7, Phase.Progressing, "checkout-old"));
    RolloutObservation observation =
        new RolloutObservation(true, true, "Paused", 1, "stable-hash", "canary-hash", 2, 2);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), observation);

    assertEquals(Phase.Paused, result.status().phase());
    assertEquals(0, result.status().currentStep());
    assertEquals("checkout-stable-hash", result.status().stableRevision());
    assertEquals("checkout-canary-hash", result.status().canaryRevision());
    assertEquals("ArgoRolloutPaused", conditionReason(result.status(), "Ready"));
    assertEquals(new ReleasePlanStatus.ResourceReference("checkout"), result.status().rolloutRef());
  }

  @Test
  void bootstrapRecordsReadyStableRevisionWithoutClaimingGateSuccess() {
    var plan = OperatorTestFixtures.plan(8);
    plan.setStatus(activeStatus(8, Phase.Bootstrapping, null));
    RolloutObservation observation =
        new RolloutObservation(true, true, "Healthy", null, "stable-hash", "stable-hash", 2, 2);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), observation);

    assertEquals(Phase.Bootstrapping, result.status().phase());
    assertEquals(2, result.status().currentStep());
    assertEquals("checkout-stable-hash", result.status().stableRevision());
    assertNull(result.status().canaryRevision());
    assertEquals("BootstrapReadyAwaitingGate", conditionReason(result.status(), "Ready"));
  }

  @Test
  void staleRolloutStatusCannotOverwriteCurrentReleaseEvidence() {
    var plan = OperatorTestFixtures.plan(9);
    plan.setStatus(activeStatus(9, Phase.Progressing, "checkout-current-stable"));
    RolloutObservation stale =
        new RolloutObservation(true, false, "Paused", 1, "stale-stable", "stale-canary", 2, 1);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), stale);

    assertEquals(Phase.Progressing, result.status().phase());
    assertEquals("checkout-current-stable", result.status().stableRevision());
    assertEquals("checkout-current-canary", result.status().canaryRevision());
    assertEquals("RolloutStatusStale", conditionReason(result.status(), "Ready"));
  }

  @Test
  void degradedCandidateIsReportedWithoutClaimingRollbackWasStarted() {
    var plan = OperatorTestFixtures.plan(10);
    plan.setStatus(activeStatus(10, Phase.Progressing, "checkout-stable"));
    RolloutObservation degraded =
        new RolloutObservation(true, true, "Degraded", 1, "stable", "candidate", 10, 8);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), degraded);

    assertEquals(Phase.Progressing, result.status().phase());
    assertEquals("ArgoRolloutDegraded", conditionReason(result.status(), "Ready"));
    assertEquals("True", conditionStatus(result.status(), "Degraded"));
  }

  @Test
  void childResumeCannotClaimAnApprovedReleaseGuardPromotion() {
    var plan = OperatorTestFixtures.plan(12);
    plan.setStatus(activeStatus(12, Phase.Paused, "checkout-stable"));
    RolloutObservation progressing =
        new RolloutObservation(true, true, "Progressing", 2, "stable", "candidate", 10, 9);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), progressing);

    assertEquals(Phase.Paused, result.status().phase());
    assertEquals("ArgoRolloutObserved", conditionReason(result.status(), "Ready"));
  }

  @Test
  void degradedBootstrapFailsWithoutClaimingRollback() {
    var plan = OperatorTestFixtures.plan(11);
    plan.setStatus(activeStatus(11, Phase.Bootstrapping, null));
    RolloutObservation degraded =
        new RolloutObservation(true, true, "Degraded", 0, null, "candidate", 10, 0);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), degraded);

    assertEquals(Phase.Failed, result.status().phase());
    assertEquals("ArgoRolloutDegraded", conditionReason(result.status(), "Ready"));
  }

  private static ReleasePlanStatus validatingStatus(long generation, String stableRevision) {
    return new ReleasePlanStatus(
        generation,
        OperatorTestFixtures.UID + ":" + generation,
        Phase.Validating,
        null,
        stableRevision,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }

  private static ReleasePlanStatus activeStatus(
      long generation, Phase phase, String stableRevision) {
    return new ReleasePlanStatus(
        generation,
        OperatorTestFixtures.UID + ":" + generation,
        phase,
        0,
        stableRevision,
        "checkout-current-canary",
        new ReleasePlanStatus.ResourceReference("checkout"),
        null,
        null,
        null,
        List.of());
  }

  private static String conditionReason(ReleasePlanStatus status, String type) {
    return status.conditions().stream()
        .filter(condition -> type.equals(condition.getType()))
        .findFirst()
        .orElseThrow()
        .getReason();
  }

  private static String conditionStatus(ReleasePlanStatus status, String type) {
    return status.conditions().stream()
        .filter(condition -> type.equals(condition.getType()))
        .findFirst()
        .orElseThrow()
        .getStatus();
  }
}
