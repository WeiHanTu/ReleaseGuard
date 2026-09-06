package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseAction;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionStatus.Phase;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReleaseActionPlannerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
  private static final String NAMESPACE = "demo";
  private static final String NAME = "checkout";
  private static final String UID = "plan-uid-1234";
  private static final long GENERATION = 3L;

  private final ReleaseActionPlanner planner =
      new ReleaseActionPlanner(
          new dev.weihantu.releaseguard.api.validation.ReleaseActionValidator(), CLOCK);

  @Test
  void pausePermittedFromProgressing() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Pause);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Completed, result.status().phase());
    assertEquals("ActionExecuted", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.PauseRollout, result.effect());
    assertTrue(result.status().resultDigest().startsWith("sha256:"));
  }

  @Test
  void pausePermittedFromBootstrapping() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Pause);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Bootstrapping, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Completed, result.status().phase());
    assertEquals(ReleaseActionPlanner.Effect.PauseRollout, result.effect());
  }

  @Test
  void pauseRejectedFromPaused() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Pause);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Paused, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("InvalidSourcePhase", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.None, result.effect());
  }

  @Test
  void promotePermittedFromPausedWhenGatePassed() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Promote);
    ReleasePlanStatus.GateDecision gateDecision =
        new ReleasePlanStatus.GateDecision(
            "Successful", "AnalysisRun/run-1", "2026-08-29T11:59:00Z");
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Paused, gateDecision);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Completed, result.status().phase());
    assertEquals("ActionExecuted", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.PromoteRollout, result.effect());
  }

  @Test
  void promoteRejectedWhenGateNotPassed() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Promote);
    ReleasePlanStatus.GateDecision gateDecision =
        new ReleasePlanStatus.GateDecision("Failed", "AnalysisRun/run-1", "2026-08-29T11:59:00Z");
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Paused, gateDecision);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("GateEvidenceNotPassed", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.None, result.effect());
  }

  @Test
  void promoteRejectedWhenGateInconclusive() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Promote);
    ReleasePlanStatus.GateDecision gateDecision =
        new ReleasePlanStatus.GateDecision(
            "Inconclusive", "AnalysisRun/run-1", "2026-08-29T11:59:00Z");
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Paused, gateDecision);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("GateEvidenceNotPassed", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.None, result.effect());
  }

  @Test
  void promoteRejectedFromProgressing() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Promote);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("InvalidSourcePhase", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.None, result.effect());
  }

  @Test
  void abortInBootstrappingStopsCandidateTraffic() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Bootstrapping, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Completed, result.status().phase());
    assertEquals(ReleaseActionPlanner.Effect.AbortBootstrap, result.effect());
  }

  @Test
  void abortInProgressingInitiatesRolloutAbort() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Completed, result.status().phase());
    assertEquals(ReleaseActionPlanner.Effect.AbortRollout, result.effect());
  }

  @Test
  void retryAnalysisPermittedFromPaused() {
    ReleaseAction action = action(ReleaseActionSpec.Type.RetryAnalysis);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Paused, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Completed, result.status().phase());
    assertEquals(ReleaseActionPlanner.Effect.RetryAnalysis, result.effect());
  }

  @Test
  void retryAnalysisRejectedFromProgressing() {
    ReleaseAction action = action(ReleaseActionSpec.Type.RetryAnalysis);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("InvalidSourcePhase", result.status().reason());
  }

  @Test
  void rejectsStaleUid() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);
    plan.getMetadata().setUid("different-uid");

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("StaleTargetUid", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.None, result.effect());
  }

  @Test
  void rejectsStaleGeneration() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);
    plan.getMetadata().setGeneration(4L); // Action targeted 3L

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("StaleTargetGeneration", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.None, result.effect());
  }

  @Test
  void rejectsWhenPlanNotFound() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);

    ReleaseActionPlanner.Result result = planner.plan(action, null);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("TargetPlanNotFound", result.status().reason());
  }

  @Test
  void rejectsWhenTargetReleaseAlreadyTerminal() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Succeeded, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("TargetReleaseTerminal", result.status().reason());
  }

  @Test
  void ignoresAlreadyCompletedAction() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    ReleaseActionStatus existingStatus =
        new ReleaseActionStatus(
            Phase.Completed, "ActionExecuted", "2026-08-29T12:00:00Z", "digest", null);
    action.setStatus(existingStatus);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Completed, result.status().phase());
    assertEquals(ReleaseActionPlanner.Effect.None, result.effect());
  }

  @Test
  void reusingIdempotencyKeyWithSameRequestReturnsOriginalResult() {
    ReleaseAction action1 = action(ReleaseActionSpec.Type.Pause);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);
    ReleaseActionPlanner.Result result1 = planner.plan(action1, plan);
    action1.setStatus(result1.status());

    ReleaseAction action2 = action(ReleaseActionSpec.Type.Pause);
    action2.getMetadata().setName("checkout-action-2");

    ReleaseActionPlanner.Result result2 = planner.plan(action2, plan, java.util.List.of(action1));

    assertEquals(Phase.Completed, result2.status().phase());
    assertEquals(result1.status().resultDigest(), result2.status().resultDigest());
    assertEquals(ReleaseActionPlanner.Effect.None, result2.effect());
  }

  @Test
  void reusingIdempotencyKeyWithDifferentRequestFails() {
    ReleaseAction action1 = action(ReleaseActionSpec.Type.Pause);
    ReleasePlan plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);
    ReleaseActionPlanner.Result result1 = planner.plan(action1, plan);
    action1.setStatus(result1.status());

    ReleaseAction action2 = action(ReleaseActionSpec.Type.Abort);
    action2.getMetadata().setName("checkout-action-2");

    ReleaseActionPlanner.Result result2 = planner.plan(action2, plan, java.util.List.of(action1));

    assertEquals(Phase.Rejected, result2.status().phase());
    assertEquals("IdempotencyConflict", result2.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.None, result2.effect());
  }

  private static ReleaseAction action(ReleaseActionSpec.Type type) {
    ReleaseAction action = new ReleaseAction();
    action.setMetadata(
        new ObjectMetaBuilder().withNamespace(NAMESPACE).withName("checkout-action-1").build());
    action.setSpec(
        new ReleaseActionSpec(
            new ReleaseActionSpec.Target(NAME, UID, GENERATION),
            type,
            "Operator intervention test",
            "idemp-key-1",
            "operator@example.com",
            "2026-08-29T11:59:00Z"));
    return action;
  }

  @Test
  void promoteRejectedWhenAuditBackpressureActive() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Promote);
    ReleasePlan plan =
        planWithPhase(
            ReleasePlanStatus.Phase.Paused,
            new ReleasePlanStatus.GateDecision(
                "Successful", "AnalysisRun/run-1", "2026-08-29T11:59:00Z"));
    ReleasePlanStatus backpressuredStatus =
        new ReleasePlanStatus(
            GENERATION,
            UID + ":" + GENERATION,
            ReleasePlanStatus.Phase.Paused,
            1,
            "checkout-stable",
            "checkout-canary",
            null,
            new ReleasePlanStatus.GateDecision(
                "Successful", "AnalysisRun/run-1", "2026-08-29T11:59:00Z"),
            null,
            55, // exceeds 50
            null);
    plan.setStatus(backpressuredStatus);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Rejected, result.status().phase());
    assertEquals("AuditBackpressure", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.None, result.effect());
  }

  @Test
  void abortAllowedWhenAuditBackpressureActive() {
    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    ReleasePlan plan =
        planWithPhase(
            ReleasePlanStatus.Phase.Paused,
            new ReleasePlanStatus.GateDecision(
                "Successful", "AnalysisRun/run-1", "2026-08-29T11:59:00Z"));
    ReleasePlanStatus backpressuredStatus =
        new ReleasePlanStatus(
            GENERATION,
            UID + ":" + GENERATION,
            ReleasePlanStatus.Phase.Paused,
            1,
            "checkout-stable",
            "checkout-canary",
            null,
            new ReleasePlanStatus.GateDecision(
                "Successful", "AnalysisRun/run-1", "2026-08-29T11:59:00Z"),
            null,
            100, // exceeds 50
            null);
    plan.setStatus(backpressuredStatus);

    ReleaseActionPlanner.Result result = planner.plan(action, plan);

    assertEquals(Phase.Completed, result.status().phase());
    assertEquals("ActionExecuted", result.status().reason());
    assertEquals(ReleaseActionPlanner.Effect.AbortRollout, result.effect());
  }

  private static ReleasePlan planWithPhase(
      ReleasePlanStatus.Phase phase, ReleasePlanStatus.GateDecision gateDecision) {
    ReleasePlan plan = new ReleasePlan();
    plan.setMetadata(
        new ObjectMetaBuilder()
            .withNamespace(NAMESPACE)
            .withName(NAME)
            .withUid(UID)
            .withGeneration(GENERATION)
            .build());
    plan.setStatus(
        new ReleasePlanStatus(
            GENERATION,
            UID + ":" + GENERATION,
            phase,
            1,
            "checkout-stable",
            "checkout-canary",
            null,
            gateDecision,
            null,
            0,
            null));
    return plan;
  }
}
