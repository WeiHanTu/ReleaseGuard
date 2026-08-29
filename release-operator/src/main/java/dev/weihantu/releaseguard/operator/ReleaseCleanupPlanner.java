package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec.DeletionPolicy;

final class ReleaseCleanupPlanner {
  CleanupAction decide(DeletionPolicy deletionPolicy, CleanupObservation observation) {
    if (deletionPolicy == DeletionPolicy.OrphanStable && observation.orphanComplete()) {
      return CleanupAction.Complete;
    }
    if (!observation.rolloutPresent()) {
      if (deletionPolicy == DeletionPolicy.OrphanStable && observation.managedChildrenPresent()) {
        return CleanupAction.OrphanStable;
      }
      return observation.managedChildrenPresent()
          ? CleanupAction.DeleteChildren
          : CleanupAction.Complete;
    }
    if (!observation.abortAcknowledged()) {
      return CleanupAction.RequestAbort;
    }
    if (observation.stableRevisionPresent()
        && (!observation.stableTrafficRestored() || !observation.canaryTrafficZero())) {
      return CleanupAction.WaitForStableTraffic;
    }
    if (deletionPolicy == DeletionPolicy.OrphanStable && observation.stableRevisionPresent()) {
      return CleanupAction.OrphanStable;
    }
    return CleanupAction.DeleteChildren;
  }
}
