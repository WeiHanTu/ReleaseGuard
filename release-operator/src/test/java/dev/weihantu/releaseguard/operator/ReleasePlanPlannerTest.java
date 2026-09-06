package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidator;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.time.Clock;
import java.time.Duration;
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
    assertEquals(5, bootstrapping.desiredResources().size());
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
    assertEquals(5, result.desiredResources().size());
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
  void bootstrapWithoutObservedStableEndpointsDoesNotStartItsGate() {
    var plan = OperatorTestFixtures.plan(8);
    plan.setStatus(activeStatus(8, Phase.Bootstrapping, null));
    RolloutObservation observation =
        new RolloutObservation(true, true, "Healthy", null, "stable-hash", "stable-hash", 2, 2);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), observation);

    assertEquals(Phase.Bootstrapping, result.status().phase());
    assertEquals(2, result.status().currentStep());
    assertEquals("checkout-stable-hash", result.status().stableRevision());
    assertNull(result.status().canaryRevision());
    assertEquals("BootstrapWorkloadPending", conditionReason(result.status(), "Ready"));
    assertEquals(5, result.desiredResources().size());
  }

  @Test
  void readyBootstrapCandidateGainsAnAbsoluteGateArgoCannotSchedule() {
    var plan = OperatorTestFixtures.plan(19);
    plan.setStatus(activeStatus(19, Phase.Bootstrapping, null));

    ReconciliationPlan result =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            bootstrapping("stable-hash", true, RolloutObservation.StepAnalysis.NONE));

    assertEquals(Phase.Bootstrapping, result.status().phase());
    assertEquals("BootstrapGateStarting", conditionReason(result.status(), "Ready"));
    assertEquals(6, result.desiredResources().size());
    HasMetadata bootstrapRun = result.desiredResources().getLast();
    assertEquals("AnalysisRun", bootstrapRun.getKind());
    assertEquals("checkout-bootstrap-g19", bootstrapRun.getMetadata().getName());
    assertFalse(result.stopCandidateTraffic());
  }

  @Test
  void passedBootstrapGateRecordsTheFirstStableRevision() {
    var plan = OperatorTestFixtures.plan(20);
    plan.setStatus(activeStatus(20, Phase.Bootstrapping, null));

    ReconciliationPlan result =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            bootstrapping(
                "stable-hash",
                true,
                new RolloutObservation.StepAnalysis("checkout-bootstrap-g20", "Successful")));

    assertEquals(Phase.Succeeded, result.status().phase());
    assertEquals("BootstrapGatePassed", conditionReason(result.status(), "Ready"));
    assertEquals("checkout-stable-hash", result.status().stableRevision());
    assertNull(result.status().canaryRevision());
    assertEquals(
        new ReleasePlanStatus.GateDecision(
            "Successful", "AnalysisRun/checkout-bootstrap-g20", "2026-08-29T12:00:00Z"),
        result.status().lastGateDecision());
    assertEquals("True", conditionStatus(result.status(), "GateHealthy"));
    assertFalse(result.stopCandidateTraffic());
    assertEquals(6, result.desiredResources().size());
    assertEquals("ReleaseEvent", result.desiredResources().getLast().getKind());
    assertEquals("checkout-20-000001", result.desiredResources().getLast().getMetadata().getName());
    assertEquals("release.bootstrapped", result.status().lastTransition().type());
    assertEquals(1L, result.status().lastTransition().sequence());
    assertTrue(result.status().lastTransition().eventId().startsWith("sha256:"));
  }

  @Test
  void failedBootstrapGateStopsCandidateTrafficWithoutClaimingRollback() {
    var plan = OperatorTestFixtures.plan(21);
    plan.setStatus(activeStatus(21, Phase.Bootstrapping, null));

    ReconciliationPlan result =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            bootstrapping(
                "stable-hash",
                true,
                new RolloutObservation.StepAnalysis("checkout-bootstrap-g21", "Failed")));

    assertEquals(Phase.Failed, result.status().phase());
    assertEquals("BootstrapGateFailed", conditionReason(result.status(), "Ready"));
    assertTrue(result.stopCandidateTraffic());
    assertFalse(result.hasDesiredResources());
    assertEquals("False", conditionStatus(result.status(), "GateHealthy"));
  }

  @Test
  void inconclusiveBootstrapEvidenceLeavesTheCandidateUnadmitted() {
    var plan = OperatorTestFixtures.plan(22);
    plan.setStatus(activeStatus(22, Phase.Bootstrapping, null));

    ReconciliationPlan result =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            bootstrapping(
                "stable-hash",
                true,
                new RolloutObservation.StepAnalysis("checkout-bootstrap-g22", "Inconclusive")));

    assertEquals(Phase.Bootstrapping, result.status().phase());
    assertEquals("BootstrapGateInconclusive", conditionReason(result.status(), "Ready"));
    assertEquals("True", conditionStatus(result.status(), "Degraded"));
    assertEquals("Unknown", conditionStatus(result.status(), "GateHealthy"));
    assertEquals("Inconclusive", result.status().lastGateDecision().decision());
    assertFalse(result.stopCandidateTraffic());
    assertEquals(5, result.desiredResources().size());
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

  @Test
  void gateApprovedPromotionSucceedsAndRecordsTheAnalysisEvidence() {
    var plan = OperatorTestFixtures.plan(13);
    plan.setStatus(activeStatus(13, Phase.Progressing, "checkout-old"));

    ReconciliationPlan result =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Healthy",
                5,
                "candidate",
                "candidate",
                RolloutObservation.Abort.NONE,
                List.of(),
                new RolloutObservation.StepAnalysis("checkout-candidate-1", "Successful")));

    assertEquals(Phase.Succeeded, result.status().phase());
    assertEquals("GateApprovedPromotion", conditionReason(result.status(), "Ready"));
    assertEquals("checkout-candidate", result.status().stableRevision());
    assertNull(result.status().canaryRevision());
    assertEquals(2, result.status().currentStep());
    assertEquals(
        new ReleasePlanStatus.GateDecision(
            "Successful", "AnalysisRun/checkout-candidate-1", "2026-08-29T12:00:00Z"),
        result.status().lastGateDecision());
    assertEquals("True", conditionStatus(result.status(), "GateHealthy"));
    assertEquals("GatePassed", conditionReason(result.status(), "GateHealthy"));
  }

  @Test
  void fullTrafficWithoutObservedGateEvidenceDoesNotClaimApproval() {
    var plan = OperatorTestFixtures.plan(18);
    plan.setStatus(activeStatus(18, Phase.Progressing, "checkout-old"));

    ReconciliationPlan result =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Healthy",
                5,
                "candidate",
                "candidate",
                RolloutObservation.Abort.NONE,
                List.of(),
                RolloutObservation.StepAnalysis.NONE));

    assertEquals(Phase.Succeeded, result.status().phase());
    assertEquals("PromotionObservedWithoutGateEvidence", conditionReason(result.status(), "Ready"));
    assertNull(result.status().lastGateDecision());
    assertEquals("Unknown", conditionStatus(result.status(), "GateHealthy"));
  }

  @Test
  void failedGateRollsBackAndCompletesOnlyOnRestoredStableTraffic() {
    var plan = OperatorTestFixtures.plan(14);
    plan.setStatus(activeStatus(14, Phase.Progressing, "checkout-stable"));
    RolloutObservation.StepAnalysis failedGate =
        new RolloutObservation.StepAnalysis("checkout-candidate-1", "Failed");

    ReconciliationPlan rollingBack =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Degraded",
                0,
                "stable",
                "candidate",
                new RolloutObservation.Abort(true, true, false),
                List.of(),
                failedGate,
                false));

    assertEquals(Phase.RollingBack, rollingBack.status().phase());
    assertEquals("GateFailedRollback", conditionReason(rollingBack.status(), "Ready"));
    assertEquals("False", conditionStatus(rollingBack.status(), "GateHealthy"));
    assertEquals("rollback.started", rollingBack.status().lastTransition().type());
    assertEquals(1L, rollingBack.status().lastTransition().sequence());
    assertTrue(rollingBack.hasDesiredResources());

    plan.setStatus(rollingBack.status());
    ReconciliationPlan stillRollingBack =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Degraded",
                0,
                "stable",
                "candidate",
                new RolloutObservation.Abort(true, true, true),
                List.of(),
                failedGate,
                false));

    assertEquals(Phase.RollingBack, stillRollingBack.status().phase());
    assertEquals("rollback.started", stillRollingBack.status().lastTransition().type());

    plan.setStatus(stillRollingBack.status());
    ReconciliationPlan rolledBack =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Degraded",
                0,
                "stable",
                "candidate",
                new RolloutObservation.Abort(true, true, true),
                List.of(),
                failedGate,
                true));

    assertEquals(Phase.RolledBack, rolledBack.status().phase());
    assertEquals("StableTrafficRestored", conditionReason(rolledBack.status(), "Ready"));
    assertEquals("checkout-stable", rolledBack.status().stableRevision());
    assertEquals("rollback.completed", rolledBack.status().lastTransition().type());
    assertEquals(2L, rolledBack.status().lastTransition().sequence());
  }

  @Test
  void rollbackDeadlineExceededTransitionsToFailedPreservingEvidence() {
    var plan = OperatorTestFixtures.plan(14);
    plan.setStatus(activeStatus(14, Phase.Progressing, "checkout-stable"));
    RolloutObservation.StepAnalysis failedGate =
        new RolloutObservation.StepAnalysis("checkout-candidate-1", "Failed");

    ReconciliationPlan rollingBack =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Degraded",
                0,
                "stable",
                "candidate",
                new RolloutObservation.Abort(true, true, false),
                List.of(),
                failedGate,
                false));

    assertEquals(Phase.RollingBack, rollingBack.status().phase());
    assertEquals("rollback.started", rollingBack.status().lastTransition().type());

    Clock laterClock = Clock.fixed(Instant.parse("2026-08-29T12:01:01Z"), ZoneOffset.UTC);
    ReleasePlanPlanner laterPlanner =
        new ReleasePlanPlanner(
            new ReleasePlanValidator(),
            new ReleaseResourceRenderer(),
            new ReleasePhaseMachine(),
            laterClock);

    plan.setStatus(rollingBack.status());
    ReconciliationPlan deadlineExceeded =
        laterPlanner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Degraded",
                0,
                "stable",
                "candidate",
                new RolloutObservation.Abort(true, true, false),
                List.of(),
                failedGate,
                false));

    assertEquals(Phase.Failed, deadlineExceeded.status().phase());
    assertEquals("RollbackDeadlineExceeded", conditionReason(deadlineExceeded.status(), "Ready"));
    assertEquals("True", conditionStatus(deadlineExceeded.status(), "Degraded"));
    assertEquals(
        "RollbackDeadlineExceeded", conditionReason(deadlineExceeded.status(), "Degraded"));
    assertEquals("rollback.failed", deadlineExceeded.status().lastTransition().type());
    assertEquals(2L, deadlineExceeded.status().lastTransition().sequence());
    assertEquals("checkout-stable", deadlineExceeded.status().stableRevision());
    assertEquals("checkout-candidate", deadlineExceeded.status().canaryRevision());
    assertNotNull(deadlineExceeded.status().lastGateDecision());
    assertEquals("Failed", deadlineExceeded.status().lastGateDecision().decision());
    assertTrue(deadlineExceeded.hasDesiredResources());
    assertEquals(1, deadlineExceeded.desiredResources().size());
    assertEquals("ReleaseEvent", deadlineExceeded.desiredResources().getFirst().getKind());
    assertEquals(
        "checkout-14-000002",
        deadlineExceeded.desiredResources().getFirst().getMetadata().getName());
    assertNull(deadlineExceeded.requeueAfter());
  }

  @Test
  void rollbackRequeueBoundsRemainingDeadline() {
    var plan = OperatorTestFixtures.plan(14);
    plan.setStatus(activeStatus(14, Phase.Progressing, "checkout-stable"));
    RolloutObservation.StepAnalysis failedGate =
        new RolloutObservation.StepAnalysis("checkout-candidate-1", "Failed");

    ReconciliationPlan rollingBack =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Degraded",
                0,
                "stable",
                "candidate",
                new RolloutObservation.Abort(true, true, false),
                List.of(),
                failedGate,
                false));

    assertEquals(Phase.RollingBack, rollingBack.status().phase());
    assertEquals(Duration.ofSeconds(5), rollingBack.requeueAfter());

    Clock nearDeadlineClock = Clock.fixed(Instant.parse("2026-08-29T12:00:58Z"), ZoneOffset.UTC);
    ReleasePlanPlanner nearDeadlinePlanner =
        new ReleasePlanPlanner(
            new ReleasePlanValidator(),
            new ReleaseResourceRenderer(),
            new ReleasePhaseMachine(),
            nearDeadlineClock);

    plan.setStatus(rollingBack.status());
    ReconciliationPlan nearDeadline =
        nearDeadlinePlanner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Degraded",
                0,
                "stable",
                "candidate",
                new RolloutObservation.Abort(true, true, false),
                List.of(),
                failedGate,
                false));

    assertEquals(Phase.RollingBack, nearDeadline.status().phase());
    assertEquals(Duration.ofSeconds(2), nearDeadline.requeueAfter());
  }

  @Test
  void inconclusiveGatePausesWithoutPromotingOrRollingBack() {
    var plan = OperatorTestFixtures.plan(15);
    plan.setStatus(activeStatus(15, Phase.Progressing, "checkout-stable"));

    ReconciliationPlan result =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Paused",
                1,
                "stable",
                "candidate",
                RolloutObservation.Abort.NONE,
                List.of(RolloutObservation.INCONCLUSIVE_ANALYSIS_PAUSE),
                new RolloutObservation.StepAnalysis("checkout-candidate-1", "Inconclusive")));

    assertEquals(Phase.Paused, result.status().phase());
    assertEquals(0, result.status().currentStep());
    assertEquals("GateInconclusivePause", conditionReason(result.status(), "Ready"));
    assertEquals("Unknown", conditionStatus(result.status(), "GateHealthy"));
    assertEquals("GateInconclusive", conditionReason(result.status(), "GateHealthy"));
    assertEquals("Inconclusive", result.status().lastGateDecision().decision());
  }

  @Test
  void anUnchangedGateVerdictDoesNotRewriteTheRecordedObservationTime() {
    var plan = OperatorTestFixtures.plan(16);
    plan.setStatus(activeStatus(16, Phase.Progressing, "checkout-stable"));
    RolloutObservation observation =
        gated(
            "Progressing",
            1,
            "stable",
            "candidate",
            RolloutObservation.Abort.NONE,
            List.of(),
            new RolloutObservation.StepAnalysis("checkout-candidate-1", "Successful"));

    ReconciliationPlan first = planner.plan(plan, OperatorTestFixtures.policies(), observation);
    plan.setStatus(first.status());
    ReconciliationPlan later =
        new ReleasePlanPlanner(
                new ReleasePlanValidator(),
                new ReleaseResourceRenderer(),
                new ReleasePhaseMachine(),
                Clock.offset(CLOCK, Duration.ofMinutes(5)))
            .plan(plan, OperatorTestFixtures.policies(), observation);

    assertEquals(first.status().lastGateDecision(), later.status().lastGateDecision());
    assertEquals(first.status(), later.status());
  }

  @Test
  void bootstrapAbortFailsWithoutClaimingRollback() {
    var plan = OperatorTestFixtures.plan(17);
    plan.setStatus(activeStatus(17, Phase.Bootstrapping, null));

    ReconciliationPlan result =
        planner.plan(
            plan,
            OperatorTestFixtures.policies(),
            gated(
                "Degraded",
                0,
                null,
                "candidate",
                new RolloutObservation.Abort(true, true, true),
                List.of(),
                RolloutObservation.StepAnalysis.NONE));

    assertEquals(Phase.Failed, result.status().phase());
    assertEquals("BootstrapAborted", conditionReason(result.status(), "Ready"));
    assertTrue(result.stopCandidateTraffic());
    assertFalse(result.hasDesiredResources());
  }

  private static RolloutObservation gated(
      String argoPhase,
      Integer currentStepIndex,
      String stablePodHash,
      String currentPodHash,
      RolloutObservation.Abort abort,
      List<String> pauseReasons,
      RolloutObservation.StepAnalysis stepAnalysis) {
    return gated(
        argoPhase,
        currentStepIndex,
        stablePodHash,
        currentPodHash,
        abort,
        pauseReasons,
        stepAnalysis,
        true);
  }

  private static RolloutObservation gated(
      String argoPhase,
      Integer currentStepIndex,
      String stablePodHash,
      String currentPodHash,
      RolloutObservation.Abort abort,
      List<String> pauseReasons,
      RolloutObservation.StepAnalysis stepAnalysis,
      boolean stableTrafficReady) {
    return new RolloutObservation(
        true,
        true,
        argoPhase,
        currentStepIndex,
        stablePodHash,
        currentPodHash,
        10,
        10,
        stableTrafficReady,
        abort,
        pauseReasons,
        stepAnalysis,
        RolloutObservation.StepAnalysis.NONE);
  }

  private static RolloutObservation bootstrapping(
      String podHash, boolean stableTrafficReady, RolloutObservation.StepAnalysis gate) {
    return new RolloutObservation(
        true,
        true,
        "Healthy",
        null,
        podHash,
        podHash,
        10,
        10,
        stableTrafficReady,
        RolloutObservation.Abort.NONE,
        List.of(),
        RolloutObservation.StepAnalysis.NONE,
        gate);
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

  @Test
  void auditBackpressurePausesProgressingRelease() {
    var plan = OperatorTestFixtures.plan(5);
    plan.setStatus(activeStatus(5, Phase.Progressing, "checkout-stable"));

    RolloutObservation observation =
        new RolloutObservation(
            true,
            true,
            "Progressing",
            1,
            "stablehash",
            "canaryhash",
            2,
            2,
            false,
            RolloutObservation.Abort.NONE,
            List.of(),
            RolloutObservation.StepAnalysis.NONE,
            RolloutObservation.StepAnalysis.NONE,
            51);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), observation);

    assertEquals(Phase.Paused, result.status().phase());
    assertEquals("AuditBackpressure", conditionReason(result.status(), "AuditHealthy"));
    assertEquals("False", conditionStatus(result.status(), "AuditHealthy"));
    assertEquals("True", conditionStatus(result.status(), "Degraded"));
    assertEquals("AuditBackpressure", conditionReason(result.status(), "Degraded"));
  }

  @Test
  void auditBackpressureDoesNotBlockAbortOrRollback() {
    var plan = OperatorTestFixtures.plan(5);
    plan.setStatus(activeStatus(5, Phase.Progressing, "checkout-stable"));

    RolloutObservation observation =
        new RolloutObservation(
            true,
            true,
            "Progressing",
            1,
            "stablehash",
            "canaryhash",
            2,
            2,
            false,
            new RolloutObservation.Abort(true, true, false),
            List.of(),
            RolloutObservation.StepAnalysis.NONE,
            RolloutObservation.StepAnalysis.NONE,
            100);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), observation);

    assertEquals(Phase.RollingBack, result.status().phase());
    assertEquals("RolloutAbortObserved", conditionReason(result.status(), "Progressing"));
  }

  @Test
  void clearingAuditBackpressureResumesProgressing() {
    var plan = OperatorTestFixtures.plan(5);
    List<io.fabric8.kubernetes.api.model.Condition> pausedConditions =
        List.of(
            new io.fabric8.kubernetes.api.model.ConditionBuilder()
                .withType("AuditHealthy")
                .withStatus("False")
                .withReason("AuditBackpressure")
                .withLastTransitionTime("2026-08-29T11:50:00Z")
                .build(),
            new io.fabric8.kubernetes.api.model.ConditionBuilder()
                .withType("Progressing")
                .withStatus("False")
                .withReason("AuditBackpressure")
                .withLastTransitionTime("2026-08-29T11:50:00Z")
                .build());
    ReleasePlanStatus pausedStatus =
        new ReleasePlanStatus(
            5L,
            OperatorTestFixtures.UID + ":5",
            Phase.Paused,
            1,
            "checkout-stable",
            "checkout-canary",
            new ReleasePlanStatus.ResourceReference("checkout"),
            null,
            null,
            60,
            pausedConditions);
    plan.setStatus(pausedStatus);

    RolloutObservation observation =
        new RolloutObservation(
            true,
            true,
            "Progressing",
            1,
            "stablehash",
            "canaryhash",
            2,
            2,
            false,
            RolloutObservation.Abort.NONE,
            List.of(),
            RolloutObservation.StepAnalysis.NONE,
            RolloutObservation.StepAnalysis.NONE,
            10);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), observation);

    assertEquals(Phase.Progressing, result.status().phase());
    assertEquals("True", conditionStatus(result.status(), "AuditHealthy"));
    assertEquals("AuditStreamHealthy", conditionReason(result.status(), "AuditHealthy"));
  }

  @Test
  void auditBackpressurePreventsBootstrapPromotion() {
    var plan = OperatorTestFixtures.plan(1);
    plan.setStatus(activeStatus(1, Phase.Bootstrapping, null));

    RolloutObservation observation =
        new RolloutObservation(
            true,
            true,
            "Healthy",
            0,
            null,
            "canaryhash",
            2,
            2,
            true,
            RolloutObservation.Abort.NONE,
            List.of(),
            RolloutObservation.StepAnalysis.NONE,
            new RolloutObservation.StepAnalysis("bootstrap-analysis", "Successful"),
            55);

    ReconciliationPlan result = planner.plan(plan, OperatorTestFixtures.policies(), observation);

    assertEquals(Phase.Bootstrapping, result.status().phase());
    assertEquals("AuditBackpressure", conditionReason(result.status(), "AuditHealthy"));
  }

  private static String conditionStatus(ReleasePlanStatus status, String type) {
    return status.conditions().stream()
        .filter(condition -> type.equals(condition.getType()))
        .findFirst()
        .orElseThrow()
        .getStatus();
  }
}
