package dev.weihantu.releaseguard.operator;

record CleanupObservation(
    boolean rolloutPresent,
    boolean abortAcknowledged,
    boolean stableRevisionPresent,
    boolean stableTrafficRestored,
    boolean canaryTrafficZero,
    boolean managedChildrenPresent,
    boolean orphanComplete) {}
