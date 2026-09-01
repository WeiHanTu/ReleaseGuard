package dev.weihantu.releaseguard.operator;

import java.util.List;

record RolloutObservation(
    boolean present,
    boolean statusCurrent,
    String argoPhase,
    Integer currentStepIndex,
    String stablePodHash,
    String currentPodHash,
    Integer desiredReplicas,
    Integer readyReplicas,
    Abort abort,
    List<String> pauseReasons,
    StepAnalysis stepAnalysis) {

  static final String INCONCLUSIVE_ANALYSIS_PAUSE = "InconclusiveAnalysisRun";

  RolloutObservation {
    abort = abort == null ? Abort.NONE : abort;
    pauseReasons = pauseReasons == null ? List.of() : List.copyOf(pauseReasons);
    stepAnalysis = stepAnalysis == null ? StepAnalysis.NONE : stepAnalysis;
  }

  RolloutObservation(
      boolean present,
      boolean statusCurrent,
      String argoPhase,
      Integer currentStepIndex,
      String stablePodHash,
      String currentPodHash,
      Integer desiredReplicas,
      Integer readyReplicas) {
    this(
        present,
        statusCurrent,
        argoPhase,
        currentStepIndex,
        stablePodHash,
        currentPodHash,
        desiredReplicas,
        readyReplicas,
        Abort.NONE,
        List.of(),
        StepAnalysis.NONE);
  }

  static RolloutObservation missing() {
    return new RolloutObservation(false, false, null, null, null, null, null, null);
  }

  boolean pausedByInconclusiveGate() {
    return pauseReasons.contains(INCONCLUSIVE_ANALYSIS_PAUSE);
  }

  boolean promoted(int argoStepCount) {
    return "Healthy".equals(argoPhase)
        && currentStepIndex != null
        && currentStepIndex >= argoStepCount
        && stablePodHash != null
        && stablePodHash.equals(currentPodHash);
  }

  /**
   * Argo abort evidence. {@code canaryTrafficZero} and {@code stableTrafficRestored} are observed
   * from the traffic path rather than from Rollout status, so RG-RBK-002 completion is not claimed
   * from an accepted abort request alone.
   */
  record Abort(
      boolean requested,
      boolean acknowledged,
      boolean canaryTrafficZero,
      boolean stableTrafficRestored) {
    static final Abort NONE = new Abort(false, false, false, false);

    boolean restored() {
      return acknowledged && canaryTrafficZero && stableTrafficRestored;
    }
  }

  /** The AnalysisRun Argo is running, or last ran, for the current canary step. */
  record StepAnalysis(String name, String phase) {
    private static final List<String> TERMINAL_PHASES =
        List.of("Successful", "Failed", "Error", "Inconclusive");
    static final StepAnalysis NONE = new StepAnalysis(null, null);

    boolean decided() {
      return name != null && !name.isBlank() && TERMINAL_PHASES.contains(phase);
    }

    boolean failed() {
      return "Failed".equals(phase) || "Error".equals(phase);
    }

    boolean successful() {
      return "Successful".equals(phase);
    }
  }
}
