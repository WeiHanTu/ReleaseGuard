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
    ReconciliationPlan duplicate = planner.plan(plan, OperatorTestFixtures.policies());

    assertEquals(first.status(), duplicate.status());
    assertEquals(first.desiredResources(), duplicate.desiredResources());
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
}
