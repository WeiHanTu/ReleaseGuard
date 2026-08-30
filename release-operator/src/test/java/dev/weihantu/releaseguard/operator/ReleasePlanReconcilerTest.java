package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleasePlanReconcilerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void patchesValidationStatusThenAppliesTheCompleteDesiredSet() {
    RecordingRuntime runtime = new RecordingRuntime();
    ReleasePlanReconciler reconciler = new ReleasePlanReconciler(new ReleasePlanPlanner(), runtime);
    ReleasePlan plan = OperatorTestFixtures.plan(1);

    var validationControl = reconciler.reconcile(plan, null);

    assertTrue(validationControl.isNoUpdate());
    assertTrue(validationControl.getScheduleDelay().isPresent());
    assertEquals(Phase.Validating, plan.getStatus().phase());
    assertTrue(runtime.applied.isEmpty());

    var applyControl = reconciler.reconcile(plan, null);

    assertTrue(applyControl.isNoUpdate());
    assertFalse(applyControl.getScheduleDelay().isPresent());
    assertEquals(Phase.Bootstrapping, plan.getStatus().phase());
    assertEquals(4, runtime.applied.size());

    runtime.rolloutObservation =
        new RolloutObservation(true, true, "Healthy", null, "stable-hash", "stable-hash", 2, 2);
    reconciler.reconcile(plan, null);

    assertEquals("checkout-stable-hash", plan.getStatus().stableRevision());
    assertEquals("BootstrapReadyAwaitingGate", conditionReason(plan, "Ready"));

    int convergedStatusWrites = runtime.statusWrites;
    reconciler.reconcile(plan, null);

    assertEquals(convergedStatusWrites, runtime.statusWrites);
  }

  @Test
  void applyFailureDoesNotPublishAFalseProgressStatus() {
    ReconciliationRuntime runtime =
        new RecordingRuntime() {
          @Override
          public void apply(List<HasMetadata> resources, Context<ReleasePlan> context) {
            throw new IllegalStateException("Kubernetes API unavailable");
          }
        };
    ReleasePlanReconciler reconciler = new ReleasePlanReconciler(new ReleasePlanPlanner(), runtime);
    ReleasePlan plan = OperatorTestFixtures.plan(2);
    reconciler.reconcile(plan, null);

    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(plan, null));
    assertEquals(Phase.Validating, plan.getStatus().phase());
  }

  @Test
  void deleteCleanupAbortsWaitsAndRemovesFinalizerOnlyAfterChildrenDisappear() {
    RecordingRuntime runtime = new RecordingRuntime();
    ReleasePlanReconciler reconciler = reconciler(runtime);
    ReleasePlan plan = OperatorTestFixtures.plan(3);
    plan.getMetadata().setDeletionTimestamp("2026-08-29T11:59:30Z");
    runtime.observation = observation(true, false, true, false, false, true, false);

    var abort = reconciler.cleanup(plan, null);

    assertFalse(abort.isRemoveFinalizer());
    assertEquals(1, runtime.abortRequests);
    assertEquals(Phase.Deleting, plan.getStatus().phase());
    assertEquals("AbortRequested", conditionReason(plan, "Ready"));

    runtime.observation = observation(true, true, true, true, true, true, false);
    var deleting = reconciler.cleanup(plan, null);

    assertFalse(deleting.isRemoveFinalizer());
    assertEquals(1, runtime.deleteRequests);

    runtime.observation = observation(false, false, false, false, true, false, false);
    var complete = reconciler.cleanup(plan, null);

    assertTrue(complete.isRemoveFinalizer());
    assertEquals(2, runtime.statusWrites);
  }

  @Test
  void orphanCleanupDetachesOnlyAfterVerifiedStableRestoration() {
    RecordingRuntime runtime = new RecordingRuntime();
    ReleasePlanReconciler reconciler = reconciler(runtime);
    ReleasePlan plan =
        OperatorTestFixtures.plan(
            4,
            "demo",
            dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec.DeletionPolicy.OrphanStable);
    plan.getMetadata().setDeletionTimestamp("2026-08-29T11:59:30Z");
    runtime.observation = observation(true, true, true, false, true, true, false);

    var waiting = reconciler.cleanup(plan, null);

    assertFalse(waiting.isRemoveFinalizer());
    assertEquals(0, runtime.orphanRequests);
    assertEquals("WaitingForStableTraffic", conditionReason(plan, "Ready"));

    runtime.observation = observation(true, true, true, true, true, true, false);
    var orphaning = reconciler.cleanup(plan, null);

    assertFalse(orphaning.isRemoveFinalizer());
    assertEquals(1, runtime.orphanRequests);

    runtime.observation = observation(false, false, true, true, true, true, true);
    assertTrue(reconciler.cleanup(plan, null).isRemoveFinalizer());
  }

  @Test
  void cleanupDeadlineFallsBackToDeletingGeneratedResources() {
    RecordingRuntime runtime = new RecordingRuntime();
    ReleasePlanReconciler reconciler = reconciler(runtime);
    ReleasePlan plan =
        OperatorTestFixtures.plan(
            5,
            "demo",
            dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec.DeletionPolicy.OrphanStable);
    plan.getMetadata().setDeletionTimestamp("2026-08-29T11:58:00Z");

    var control = reconciler.cleanup(plan, null);

    assertTrue(control.isRemoveFinalizer());
    assertEquals(1, runtime.deleteRequests);
    assertEquals("True", conditionStatus(plan, "Degraded"));
    assertEquals("CleanupDeadlineExceeded", conditionReason(plan, "Degraded"));
  }

  private static ReleasePlanReconciler reconciler(RecordingRuntime runtime) {
    return new ReleasePlanReconciler(
        new ReleasePlanPlanner(),
        runtime,
        new ReleaseCleanupPlanner(),
        new CleanupPolicy(Duration.ofSeconds(60), Duration.ofSeconds(1)),
        CLOCK);
  }

  private static CleanupObservation observation(
      boolean rolloutPresent,
      boolean abortAcknowledged,
      boolean stableRevisionPresent,
      boolean stableTrafficRestored,
      boolean canaryTrafficZero,
      boolean managedChildrenPresent,
      boolean orphanComplete) {
    return new CleanupObservation(
        rolloutPresent,
        abortAcknowledged,
        stableRevisionPresent,
        stableTrafficRestored,
        canaryTrafficZero,
        managedChildrenPresent,
        orphanComplete);
  }

  private static String conditionReason(ReleasePlan plan, String type) {
    return plan.getStatus().conditions().stream()
        .filter(condition -> type.equals(condition.getType()))
        .findFirst()
        .orElseThrow()
        .getReason();
  }

  private static String conditionStatus(ReleasePlan plan, String type) {
    return plan.getStatus().conditions().stream()
        .filter(condition -> type.equals(condition.getType()))
        .findFirst()
        .orElseThrow()
        .getStatus();
  }

  private static class RecordingRuntime implements ReconciliationRuntime {
    private final List<HasMetadata> applied = new ArrayList<>();
    private CleanupObservation observation =
        observation(false, false, false, false, true, false, false);
    private RolloutObservation rolloutObservation = RolloutObservation.missing();
    private int abortRequests;
    private int deleteRequests;
    private int orphanRequests;
    private int statusWrites;

    @Override
    public Map<String, MetricPolicySpec> acceptedPolicies(
        ReleasePlan plan, Context<ReleasePlan> context) {
      return OperatorTestFixtures.policies();
    }

    @Override
    public void apply(List<HasMetadata> resources, Context<ReleasePlan> context) {
      applied.clear();
      applied.addAll(resources);
    }

    @Override
    public RolloutObservation observeRollout(ReleasePlan plan, Context<ReleasePlan> context) {
      return rolloutObservation;
    }

    @Override
    public CleanupObservation observeCleanup(ReleasePlan plan, Context<ReleasePlan> context) {
      return observation;
    }

    @Override
    public void requestAbort(ReleasePlan plan, Context<ReleasePlan> context) {
      abortRequests++;
    }

    @Override
    public void deleteManagedChildren(ReleasePlan plan, Context<ReleasePlan> context) {
      deleteRequests++;
    }

    @Override
    public void orphanStableResources(ReleasePlan plan, Context<ReleasePlan> context) {
      orphanRequests++;
    }

    @Override
    public void publishStatus(ReleasePlan plan, Context<ReleasePlan> context) {
      statusWrites++;
    }
  }
}
