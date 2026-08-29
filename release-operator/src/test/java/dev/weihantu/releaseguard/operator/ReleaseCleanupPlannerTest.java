package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec.DeletionPolicy;
import org.junit.jupiter.api.Test;

class ReleaseCleanupPlannerTest {
  private final ReleaseCleanupPlanner planner = new ReleaseCleanupPlanner();

  @Test
  void requestsAbortBeforeMutatingChildren() {
    CleanupObservation observation = observation(true, false, true, false, false, true, false);

    assertEquals(CleanupAction.RequestAbort, planner.decide(DeletionPolicy.Delete, observation));
  }

  @Test
  void waitsForStableEndpointsAndZeroCanaryTrafficAfterAbort() {
    assertEquals(
        CleanupAction.WaitForStableTraffic,
        planner.decide(
            DeletionPolicy.Delete, observation(true, true, true, false, true, true, false)));
    assertEquals(
        CleanupAction.WaitForStableTraffic,
        planner.decide(
            DeletionPolicy.Delete, observation(true, true, true, true, false, true, false)));
  }

  @Test
  void deletesBootstrapOrDeletePolicyChildren() {
    assertEquals(
        CleanupAction.DeleteChildren,
        planner.decide(
            DeletionPolicy.Delete, observation(true, true, true, true, true, true, false)));
    assertEquals(
        CleanupAction.DeleteChildren,
        planner.decide(
            DeletionPolicy.OrphanStable, observation(true, true, false, false, true, true, false)));
  }

  @Test
  void detachesOnlyAfterStableTrafficIsRestored() {
    CleanupObservation safe = observation(true, true, true, true, true, true, false);
    CleanupObservation detached = observation(false, false, true, true, true, true, true);

    assertEquals(CleanupAction.OrphanStable, planner.decide(DeletionPolicy.OrphanStable, safe));
    assertEquals(CleanupAction.Complete, planner.decide(DeletionPolicy.OrphanStable, detached));
  }

  @Test
  void continuesOrphaningWhileCanaryDescendantsAreTerminating() {
    CleanupObservation terminating = observation(false, false, true, true, true, true, false);

    assertEquals(
        CleanupAction.OrphanStable, planner.decide(DeletionPolicy.OrphanStable, terminating));
  }

  @Test
  void removesFinalizerOnlyAfterEveryDeleteTargetDisappears() {
    assertEquals(
        CleanupAction.DeleteChildren,
        planner.decide(
            DeletionPolicy.Delete, observation(false, false, false, false, true, true, false)));
    assertEquals(
        CleanupAction.Complete,
        planner.decide(
            DeletionPolicy.Delete, observation(false, false, false, false, true, false, false)));
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
}
