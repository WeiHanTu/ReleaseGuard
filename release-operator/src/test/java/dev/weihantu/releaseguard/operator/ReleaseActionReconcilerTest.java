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
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseActionReconcilerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
  private static final String NAMESPACE = "demo";
  private static final String NAME = "checkout";
  private static final String UID = "plan-uid-1234";
  private static final long GENERATION = 3L;

  @Test
  void pauseExecutesPauseRolloutAndUpdatesStatus() {
    RecordingActionRuntime runtime = new RecordingActionRuntime();
    runtime.plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);
    ReleaseActionReconciler reconciler =
        new ReleaseActionReconciler(
            new ReleaseActionPlanner(
                new dev.weihantu.releaseguard.api.validation.ReleaseActionValidator(), CLOCK),
            runtime);

    ReleaseAction action = action(ReleaseActionSpec.Type.Pause);
    UpdateControl<ReleaseAction> control = reconciler.reconcile(action, null);

    assertTrue(control.isNoUpdate());
    assertEquals(1, runtime.pausedRollouts.size());
    assertEquals("checkout", runtime.pausedRollouts.getFirst());
    assertEquals(Phase.Completed, action.getStatus().phase());
    assertEquals("ActionExecuted", action.getStatus().reason());
    assertEquals(1, runtime.statusUpdates.size());
  }

  @Test
  void promoteExecutesPromoteRolloutWhenGateSuccessful() {
    RecordingActionRuntime runtime = new RecordingActionRuntime();
    ReleasePlanStatus.GateDecision gate =
        new ReleasePlanStatus.GateDecision(
            "Successful", "AnalysisRun/run-1", "2026-08-29T11:59:00Z");
    runtime.plan = planWithPhase(ReleasePlanStatus.Phase.Paused, gate);
    ReleaseActionReconciler reconciler =
        new ReleaseActionReconciler(
            new ReleaseActionPlanner(
                new dev.weihantu.releaseguard.api.validation.ReleaseActionValidator(), CLOCK),
            runtime);

    ReleaseAction action = action(ReleaseActionSpec.Type.Promote);
    UpdateControl<ReleaseAction> control = reconciler.reconcile(action, null);

    assertTrue(control.isNoUpdate());
    assertEquals(1, runtime.promotedRollouts.size());
    assertEquals("checkout", runtime.promotedRollouts.getFirst());
    assertEquals(Phase.Completed, action.getStatus().phase());
    assertEquals(1, runtime.statusUpdates.size());
  }

  @Test
  void abortExecutesAbortBootstrapWhenBootstrapping() {
    RecordingActionRuntime runtime = new RecordingActionRuntime();
    runtime.plan = planWithPhase(ReleasePlanStatus.Phase.Bootstrapping, null);
    ReleaseActionReconciler reconciler =
        new ReleaseActionReconciler(
            new ReleaseActionPlanner(
                new dev.weihantu.releaseguard.api.validation.ReleaseActionValidator(), CLOCK),
            runtime);

    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    UpdateControl<ReleaseAction> control = reconciler.reconcile(action, null);

    assertTrue(control.isNoUpdate());
    assertEquals(1, runtime.abortedBootstraps.size());
    assertEquals(0, runtime.abortedRollouts.size());
    assertEquals(Phase.Completed, action.getStatus().phase());
  }

  @Test
  void abortExecutesAbortRolloutWhenProgressing() {
    RecordingActionRuntime runtime = new RecordingActionRuntime();
    runtime.plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);
    ReleaseActionReconciler reconciler =
        new ReleaseActionReconciler(
            new ReleaseActionPlanner(
                new dev.weihantu.releaseguard.api.validation.ReleaseActionValidator(), CLOCK),
            runtime);

    ReleaseAction action = action(ReleaseActionSpec.Type.Abort);
    UpdateControl<ReleaseAction> control = reconciler.reconcile(action, null);

    assertTrue(control.isNoUpdate());
    assertEquals(0, runtime.abortedBootstraps.size());
    assertEquals(1, runtime.abortedRollouts.size());
    assertEquals("checkout", runtime.abortedRollouts.getFirst());
    assertEquals(Phase.Completed, action.getStatus().phase());
  }

  @Test
  void retryAnalysisExecutesRetryAnalysisWhenPaused() {
    RecordingActionRuntime runtime = new RecordingActionRuntime();
    runtime.plan = planWithPhase(ReleasePlanStatus.Phase.Paused, null);
    ReleaseActionReconciler reconciler =
        new ReleaseActionReconciler(
            new ReleaseActionPlanner(
                new dev.weihantu.releaseguard.api.validation.ReleaseActionValidator(), CLOCK),
            runtime);

    ReleaseAction action = action(ReleaseActionSpec.Type.RetryAnalysis);
    UpdateControl<ReleaseAction> control = reconciler.reconcile(action, null);

    assertTrue(control.isNoUpdate());
    assertEquals(1, runtime.retriedAnalyses.size());
    assertEquals("checkout", runtime.retriedAnalyses.getFirst());
    assertEquals(Phase.Completed, action.getStatus().phase());
  }

  @Test
  void staleTargetUidRejectsWithoutChangingRolloutState() {
    RecordingActionRuntime runtime = new RecordingActionRuntime();
    runtime.plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);
    ReleaseActionReconciler reconciler =
        new ReleaseActionReconciler(
            new ReleaseActionPlanner(
                new dev.weihantu.releaseguard.api.validation.ReleaseActionValidator(), CLOCK),
            runtime);

    ReleaseAction action = action(ReleaseActionSpec.Type.Pause);
    action.setSpec(
        new ReleaseActionSpec(
            new ReleaseActionSpec.Target(NAME, "wrong-uid", GENERATION),
            ReleaseActionSpec.Type.Pause,
            "test reason",
            "key-1",
            "actor@example.com",
            "2026-08-29T11:59:00Z"));

    UpdateControl<ReleaseAction> control = reconciler.reconcile(action, null);

    assertTrue(control.isNoUpdate());
    assertEquals(0, runtime.pausedRollouts.size());
    assertEquals(Phase.Rejected, action.getStatus().phase());
    assertEquals("StaleTargetUid", action.getStatus().reason());
  }

  @Test
  void terminalActionIsNotReExecuted() {
    RecordingActionRuntime runtime = new RecordingActionRuntime();
    runtime.plan = planWithPhase(ReleasePlanStatus.Phase.Progressing, null);
    ReleaseActionReconciler reconciler =
        new ReleaseActionReconciler(
            new ReleaseActionPlanner(
                new dev.weihantu.releaseguard.api.validation.ReleaseActionValidator(), CLOCK),
            runtime);

    ReleaseAction action = action(ReleaseActionSpec.Type.Pause);
    action.setStatus(
        new ReleaseActionStatus(
            Phase.Completed, "ActionExecuted", "2026-08-29T11:59:00Z", "digest-1", List.of()));

    UpdateControl<ReleaseAction> control = reconciler.reconcile(action, null);

    assertTrue(control.isNoUpdate());
    assertEquals(0, runtime.pausedRollouts.size());
    assertEquals(0, runtime.statusUpdates.size());
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

  private static final class RecordingActionRuntime implements ActionRuntime {
    ReleasePlan plan;
    final List<ReleaseAction> actions = new ArrayList<>();
    final List<String> pausedRollouts = new ArrayList<>();
    final List<String> promotedRollouts = new ArrayList<>();
    final List<String> abortedRollouts = new ArrayList<>();
    final List<String> abortedBootstraps = new ArrayList<>();
    final List<String> retriedAnalyses = new ArrayList<>();
    final List<ReleaseAction> statusUpdates = new ArrayList<>();

    @Override
    public ReleasePlan getPlan(String namespace, String name, Context<ReleaseAction> context) {
      return plan;
    }

    @Override
    public List<ReleaseAction> listActions(String namespace, Context<ReleaseAction> context) {
      return actions;
    }

    @Override
    public void pauseRollout(String namespace, String name, Context<ReleaseAction> context) {
      pausedRollouts.add(name);
    }

    @Override
    public void promoteRollout(String namespace, String name, Context<ReleaseAction> context) {
      promotedRollouts.add(name);
    }

    @Override
    public void abortRollout(String namespace, String name, Context<ReleaseAction> context) {
      abortedRollouts.add(name);
    }

    @Override
    public void abortBootstrap(String namespace, String name, Context<ReleaseAction> context) {
      abortedBootstraps.add(name);
    }

    @Override
    public void retryAnalysis(String namespace, String name, Context<ReleaseAction> context) {
      retriedAnalyses.add(name);
    }

    @Override
    public void publishStatus(ReleaseAction action, Context<ReleaseAction> context) {
      statusUpdates.add(action);
    }
  }
}
