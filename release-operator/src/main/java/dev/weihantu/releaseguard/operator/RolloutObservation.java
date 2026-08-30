package dev.weihantu.releaseguard.operator;

record RolloutObservation(
    boolean present,
    boolean statusCurrent,
    String argoPhase,
    Integer currentStepIndex,
    String stablePodHash,
    String currentPodHash,
    Integer desiredReplicas,
    Integer readyReplicas) {

  static RolloutObservation missing() {
    return new RolloutObservation(false, false, null, null, null, null, null, null);
  }
}
