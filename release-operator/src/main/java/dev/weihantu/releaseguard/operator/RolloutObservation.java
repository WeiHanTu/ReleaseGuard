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
    boolean stableTrafficReady,
    Abort abort,
    List<String> pauseReasons,
    StepAnalysis stepAnalysis,
    StepAnalysis bootstrapAnalysis) {

  static final String INCONCLUSIVE_ANALYSIS_PAUSE = "InconclusiveAnalysisRun";

  RolloutObservation {
    abort = abort == null ? Abort.NONE : abort;
    pauseReasons = pauseReasons == null ? List.of() : List.copyOf(pauseReasons);
    stepAnalysis = stepAnalysis == null ? StepAnalysis.NONE : stepAnalysis;
    bootstrapAnalysis = bootstrapAnalysis == null ? StepAnalysis.NONE : bootstrapAnalysis;
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
        false,
        Abort.NONE,
        List.of(),
        StepAnalysis.NONE,
        StepAnalysis.NONE);
  }

  static RolloutObservation missing() {
    return new RolloutObservation(false, false, null, null, null, null, null, null);
  }

  boolean pausedByInconclusiveGate() {
    return pauseReasons.contains(INCONCLUSIVE_ANALYSIS_PAUSE);
  }

  boolean promoted(int argoStepCount) {
    return fullyPromoted() && currentStepIndex != null && currentStepIndex >= argoStepCount;
  }

  /**
   * RG-BOOT-002: the bootstrap candidate is ready only when Argo reports a healthy, fully promoted
   * revision and the stable Service actually has ready endpoints behind it.
   */
  boolean bootstrapWorkloadReady() {
    return fullyPromoted() && stableTrafficReady;
  }

  /** RG-RBK-002: rollback completes on observed traffic, not on an accepted abort request. */
  boolean rollbackComplete() {
    return abort.acknowledged() && abort.canaryTrafficZero() && stableTrafficReady;
  }

  private boolean fullyPromoted() {
    return statusCurrent
        && "Healthy".equals(argoPhase)
        && stablePodHash != null
        && !stablePodHash.isBlank()
        && stablePodHash.equals(currentPodHash);
  }

  /** Argo abort evidence, paired with the observed canary traffic weight. */
  record Abort(boolean requested, boolean acknowledged, boolean canaryTrafficZero) {
    static final Abort NONE = new Abort(false, false, false);
  }

  /** An AnalysisRun Argo is running, or last ran, for this release. */
  record StepAnalysis(String name, String phase) {
    private static final List<String> TERMINAL_PHASES =
        List.of("Successful", "Failed", "Error", "Inconclusive");
    static final StepAnalysis NONE = new StepAnalysis(null, null);

    boolean created() {
      return name != null && !name.isBlank();
    }

    boolean decided() {
      return created() && TERMINAL_PHASES.contains(phase);
    }

    boolean failed() {
      return "Failed".equals(phase) || "Error".equals(phase);
    }

    boolean successful() {
      return "Successful".equals(phase);
    }
  }
}
