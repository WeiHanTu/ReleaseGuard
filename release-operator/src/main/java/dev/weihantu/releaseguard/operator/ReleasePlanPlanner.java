package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidationContext;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidator;
import dev.weihantu.releaseguard.api.validation.ValidationError;
import dev.weihantu.releaseguard.api.validation.ValidationResult;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReleasePlanPlanner {
  private static final Duration IMMEDIATE_REQUEUE = Duration.ofMillis(1);
  // Rollback completion depends on Endpoints, which the operator does not watch.
  private static final Duration ROLLBACK_REQUEUE = Duration.ofSeconds(5);

  private final ReleasePlanValidator validator;
  private final ReleaseResourceRenderer renderer;
  private final ReleasePhaseMachine phaseMachine;
  private final Clock clock;

  public ReleasePlanPlanner() {
    this(
        new ReleasePlanValidator(),
        new ReleaseResourceRenderer(
            MetricGateAnalysisConfiguration.fromEnvironment(System.getenv())),
        new ReleasePhaseMachine(),
        Clock.systemUTC());
  }

  ReleasePlanPlanner(
      ReleasePlanValidator validator,
      ReleaseResourceRenderer renderer,
      ReleasePhaseMachine phaseMachine,
      Clock clock) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.phaseMachine = Objects.requireNonNull(phaseMachine, "phaseMachine");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public ReconciliationPlan plan(
      ReleasePlan plan, Map<String, MetricPolicySnapshot> acceptedPolicies) {
    return plan(plan, acceptedPolicies, RolloutObservation.missing());
  }

  ReconciliationPlan plan(
      ReleasePlan plan,
      Map<String, MetricPolicySnapshot> acceptedPolicies,
      RolloutObservation rolloutObservation) {
    ReleaseResourceRenderer.Identity identity = ReleaseResourceRenderer.Identity.from(plan);
    ReleasePlanStatus current = plan.getStatus();
    long generation = identity.generation();

    if (isUnobservedGeneration(current, generation)) {
      if (current != null && isActive(current.phase())) {
        return failed(
            current,
            identity,
            "RG-PLAN-011",
            "a newer generation appeared while the previous release was active");
      }
      phaseMachine.requireTransition(current == null ? null : current.phase(), Phase.Validating);
      ReleasePlanStatus status =
          status(
              current,
              identity,
              Phase.Validating,
              null,
              current == null ? null : current.stableRevision(),
              null,
              null,
              "ValidationPending",
              "Validating release plan generation " + generation);
      return new ReconciliationPlan(status, List.of(), IMMEDIATE_REQUEUE);
    }

    ValidationResult validation =
        validator.validate(
            plan.getSpec(),
            ReleasePlanValidationContext.releaseEnvironment(
                identity.namespace(), policySpecs(acceptedPolicies)));
    if (!validation.isValid()) {
      ValidationError first = validation.errors().getFirst();
      return failed(current, identity, first.requirement(), first.path() + ": " + first.message());
    }

    Phase currentPhase = current.phase();
    if (currentPhase == Phase.Validating) {
      Phase next = current.stableRevision() == null ? Phase.Bootstrapping : Phase.Progressing;
      phaseMachine.requireTransition(currentPhase, next);
      String revision = ReleaseResourceRenderer.revision(identity, plan.getSpec());
      ReleasePlanStatus status =
          status(
              current,
              identity,
              next,
              0,
              current.stableRevision(),
              revision,
              current.lastGateDecision(),
              next == Phase.Bootstrapping ? "BootstrapPrepared" : "RolloutPrepared",
              "Desired rollout resources are prepared and metric-gated progression is pending");
      return new ReconciliationPlan(status, renderer.render(plan, acceptedPolicies), null);
    }

    if (currentPhase == Phase.Bootstrapping
        || currentPhase == Phase.Progressing
        || currentPhase == Phase.Paused
        || currentPhase == Phase.RollingBack) {
      return activeRelease(
          plan, current, identity, currentPhase, acceptedPolicies, rolloutObservation);
    }

    return new ReconciliationPlan(current, List.of(), null);
  }

  private ReconciliationPlan activeRelease(
      ReleasePlan plan,
      ReleasePlanStatus current,
      ReleaseResourceRenderer.Identity identity,
      Phase currentPhase,
      Map<String, MetricPolicySnapshot> acceptedPolicies,
      RolloutObservation observation) {
    ReleasePlanSpec spec = plan.getSpec();
    Phase phase = projectedPhase(currentPhase, observation, spec);
    phaseMachine.requireTransition(currentPhase, phase);
    String stableRevision = current.stableRevision();
    String canaryRevision = current.canaryRevision();
    Integer currentStep = current.currentStep();
    RolloutObservation.StepAnalysis gate =
        currentPhase == Phase.Bootstrapping
            ? observation.bootstrapAnalysis()
            : observation.stepAnalysis();
    ReleasePlanStatus.GateDecision gateDecision = gateDecision(current, observation, gate);
    String reason;
    String message;

    if (!observation.present()) {
      reason = "RolloutPending";
      message = "Waiting for the generated Argo Rollout";
    } else if (!observation.statusCurrent()) {
      reason = "RolloutStatusStale";
      message = "Waiting for Argo Rollouts to observe the current Rollout generation";
    } else {
      stableRevision = qualifiedRevision(identity.name(), observation.stablePodHash());
      canaryRevision = candidateRevision(identity.name(), observation);
      currentStep = logicalStep(observation.currentStepIndex(), spec);
      reason = observationReason(currentPhase, phase, observation, gateDecision);
      message = observationMessage(currentPhase, phase, observation, gateDecision);
    }
    if (currentPhase == Phase.Bootstrapping && phase == Phase.Succeeded) {
      // RG-BOOT-004: the admitted bootstrap candidate becomes the first stable revision.
      canaryRevision = null;
    }

    ReleasePlanStatus status =
        status(
            current,
            identity,
            phase,
            currentStep,
            stableRevision,
            canaryRevision,
            gateDecision,
            reason,
            message);
    return new ReconciliationPlan(
        status,
        desiredResources(plan, acceptedPolicies, currentPhase, phase, observation),
        phase == Phase.RollingBack ? ROLLBACK_REQUEUE : null,
        currentPhase == Phase.Bootstrapping && phase == Phase.Failed);
  }

  /**
   * A failed bootstrap stops asserting its workload so the candidate can be scaled to zero, and a
   * ready bootstrap candidate gains the administrator-approved absolute gate Argo cannot schedule
   * for it (RG-BOOT-002).
   */
  private List<HasMetadata> desiredResources(
      ReleasePlan plan,
      Map<String, MetricPolicySnapshot> acceptedPolicies,
      Phase currentPhase,
      Phase phase,
      RolloutObservation observation) {
    if (phase == Phase.Failed) {
      return List.of();
    }
    List<HasMetadata> desired = new ArrayList<>(renderer.render(plan, acceptedPolicies));
    if (currentPhase == Phase.Bootstrapping
        && observation.bootstrapWorkloadReady()
        && !observation.bootstrapAnalysis().decided()) {
      desired.addAll(
          renderer.renderBootstrapAnalysis(plan, acceptedPolicies, observation.currentPodHash()));
    }
    return List.copyOf(desired);
  }

  /**
   * Records the Argo verdict for the current canary step. The decision is only refreshed when Argo
   * reports a different terminal AnalysisRun outcome, so repeated reconciliation of unchanged input
   * produces an identical status (RG-REC-001).
   */
  private ReleasePlanStatus.GateDecision gateDecision(
      ReleasePlanStatus current,
      RolloutObservation observation,
      RolloutObservation.StepAnalysis analysis) {
    ReleasePlanStatus.GateDecision previous = current.lastGateDecision();
    if (!observation.present() || !observation.statusCurrent() || !analysis.decided()) {
      return previous;
    }
    String evidenceRef = "AnalysisRun/" + analysis.name();
    String decision = analysis.phase();
    if (previous != null
        && evidenceRef.equals(previous.evidenceRef())
        && decision.equals(previous.decision())) {
      return previous;
    }
    return new ReleasePlanStatus.GateDecision(decision, evidenceRef, Instant.now(clock).toString());
  }

  private static Map<String, MetricPolicySpec> policySpecs(
      Map<String, MetricPolicySnapshot> snapshots) {
    Map<String, MetricPolicySpec> specs = new HashMap<>();
    snapshots.forEach((name, snapshot) -> specs.put(name, snapshot.spec()));
    return Map.copyOf(specs);
  }

  private static Phase projectedPhase(
      Phase currentPhase, RolloutObservation observation, ReleasePlanSpec spec) {
    if (!observation.present() || !observation.statusCurrent()) {
      return currentPhase;
    }
    if (observation.abort().requested()) {
      return abortedPhase(currentPhase, observation);
    }
    if (currentPhase == Phase.RollingBack || currentPhase == Phase.Paused) {
      // Leaving Paused requires an operator action; a resumed child alone cannot promote.
      return currentPhase;
    }
    if ("Degraded".equals(observation.argoPhase())) {
      return currentPhase == Phase.Bootstrapping ? Phase.Failed : currentPhase;
    }
    if (currentPhase == Phase.Bootstrapping) {
      return bootstrapPhase(observation);
    }
    if (observation.pausedByInconclusiveGate()
        || "Paused".equals(observation.argoPhase())
        || "Suspended".equals(observation.argoPhase())) {
      return Phase.Paused;
    }
    if (observation.promoted(ReleaseResourceRenderer.argoStepCount(spec))) {
      return Phase.Succeeded;
    }
    return currentPhase;
  }

  /**
   * RG-BOOT-002 admits a bootstrap candidate only on a passed absolute gate. A breached threshold
   * fails it closed, while inconclusive evidence or a provider error leaves it unadmitted rather
   * than promoting or claiming a rollback it cannot perform.
   */
  private static Phase bootstrapPhase(RolloutObservation observation) {
    RolloutObservation.StepAnalysis gate = observation.bootstrapAnalysis();
    if (gate.successful()) {
      return Phase.Succeeded;
    }
    if ("Failed".equals(gate.phase())) {
      return Phase.Failed;
    }
    return Phase.Bootstrapping;
  }

  private static Phase abortedPhase(Phase currentPhase, RolloutObservation observation) {
    if (currentPhase == Phase.Bootstrapping) {
      // RG-BOOT-003: a bootstrap has no baseline to restore, so it fails instead of rolling back.
      return Phase.Failed;
    }
    if (currentPhase == Phase.RollingBack && observation.rollbackComplete()) {
      return Phase.RolledBack;
    }
    return Phase.RollingBack;
  }

  private static String observationReason(
      Phase currentPhase,
      Phase phase,
      RolloutObservation observation,
      ReleasePlanStatus.GateDecision gateDecision) {
    if (phase == Phase.Succeeded) {
      if (currentPhase == Phase.Bootstrapping) {
        return "BootstrapGatePassed";
      }
      // Argo can be promoted by hand. Full traffic alone must not claim ReleaseGuard gate approval.
      return decided(gateDecision, "Successful")
          ? "GateApprovedPromotion"
          : "PromotionObservedWithoutGateEvidence";
    }
    if (phase == Phase.Failed && currentPhase == Phase.Bootstrapping) {
      if (observation.abort().requested()) {
        return "BootstrapAborted";
      }
      return "Failed".equals(observation.bootstrapAnalysis().phase())
          ? "BootstrapGateFailed"
          : "ArgoRolloutDegraded";
    }
    if (currentPhase == Phase.Bootstrapping) {
      return bootstrapReason(observation);
    }
    if (phase == Phase.RolledBack) {
      return "StableTrafficRestored";
    }
    if (phase == Phase.RollingBack) {
      return failedGate(observation, gateDecision) ? "GateFailedRollback" : "RolloutAbortObserved";
    }
    if (observation.pausedByInconclusiveGate()) {
      return "GateInconclusivePause";
    }
    if ("Degraded".equals(observation.argoPhase())) {
      return "ArgoRolloutDegraded";
    }
    if ("Paused".equals(observation.argoPhase()) || "Suspended".equals(observation.argoPhase())) {
      return "ArgoRolloutPaused";
    }
    return "ArgoRolloutObserved";
  }

  private static String bootstrapReason(RolloutObservation observation) {
    RolloutObservation.StepAnalysis gate = observation.bootstrapAnalysis();
    if (gate.decided()) {
      return "BootstrapGateInconclusive";
    }
    if (gate.created()) {
      return "BootstrapGateRunning";
    }
    return observation.bootstrapWorkloadReady()
        ? "BootstrapGateStarting"
        : "BootstrapWorkloadPending";
  }

  private static String bootstrapMessage(String reason) {
    return switch (reason) {
      case "BootstrapGateInconclusive" ->
          "Bootstrap metric evidence was inconclusive; the candidate is not admitted as stable";
      case "BootstrapGateRunning" ->
          "The administrator-approved absolute bootstrap gate is running";
      case "BootstrapGateStarting" ->
          "Stable endpoints are ready; starting the administrator-approved absolute bootstrap gate";
      default -> "Waiting for a ready bootstrap workload before the absolute gate can run";
    };
  }

  private static String observationMessage(
      Phase currentPhase,
      Phase phase,
      RolloutObservation observation,
      ReleasePlanStatus.GateDecision gateDecision) {
    if (phase == Phase.Succeeded) {
      if (currentPhase == Phase.Bootstrapping) {
        return "The absolute bootstrap gate passed and the candidate became the stable revision";
      }
      return decided(gateDecision, "Successful")
          ? "Every declared gate passed and Argo Rollouts promoted the candidate to full traffic"
          : "Argo Rollouts reached full traffic without an observed terminal gate decision";
    }
    if (phase == Phase.Failed && currentPhase == Phase.Bootstrapping) {
      return "Bootstrap failed; candidate traffic is being stopped and no rollback is claimed";
    }
    if (currentPhase == Phase.Bootstrapping) {
      return bootstrapMessage(bootstrapReason(observation));
    }
    if (phase == Phase.RolledBack) {
      return "Canary traffic is zero and the stable Service has ready endpoints";
    }
    if (phase == Phase.RollingBack) {
      return failedGate(observation, gateDecision)
          ? "Argo Rollouts is restoring the stable revision after a failed gate"
          : "Argo Rollouts is restoring the stable revision after an abort request";
    }
    if (observation.pausedByInconclusiveGate()) {
      return "Metric evidence was inconclusive; promotion requires an explicit operator action";
    }
    return "Observed Argo Rollout phase " + observation.argoPhase();
  }

  private static boolean decided(ReleasePlanStatus.GateDecision decision, String phase) {
    return decision != null && phase.equals(decision.decision());
  }

  private static boolean failedGate(
      RolloutObservation observation, ReleasePlanStatus.GateDecision decision) {
    return observation.stepAnalysis().failed()
        || decided(decision, "Failed")
        || decided(decision, "Error");
  }

  private static String candidateRevision(String name, RolloutObservation observation) {
    if (observation.currentPodHash() == null
        || observation.currentPodHash().isBlank()
        || observation.currentPodHash().equals(observation.stablePodHash())) {
      return null;
    }
    return qualifiedRevision(name, observation.currentPodHash());
  }

  private static String qualifiedRevision(String name, String podHash) {
    if (podHash == null || podHash.isBlank()) {
      return null;
    }
    return podHash.startsWith(name + "-") ? podHash : name + "-" + podHash;
  }

  private static Integer logicalStep(Integer argoStepIndex, ReleasePlanSpec spec) {
    int releaseStepCount = spec.rollout().steps().size();
    if (releaseStepCount == 0) {
      return null;
    }
    if (argoStepIndex == null) {
      return releaseStepCount - 1;
    }
    return ReleaseResourceRenderer.releaseStep(spec, argoStepIndex);
  }

  private ReconciliationPlan failed(
      ReleasePlanStatus current,
      ReleaseResourceRenderer.Identity identity,
      String reason,
      String message) {
    if (current != null
        && current.observedGeneration() != null
        && current.observedGeneration() == identity.generation()) {
      phaseMachine.requireTransition(current.phase(), Phase.Failed);
    }
    ReleasePlanStatus failed =
        status(
            current,
            identity,
            Phase.Failed,
            current == null ? null : current.currentStep(),
            current == null ? null : current.stableRevision(),
            current == null ? null : current.canaryRevision(),
            sameGeneration(current, identity.generation()) ? current.lastGateDecision() : null,
            reason,
            message);
    return new ReconciliationPlan(failed, List.of(), null);
  }

  private ReleasePlanStatus status(
      ReleasePlanStatus current,
      ReleaseResourceRenderer.Identity identity,
      Phase phase,
      Integer currentStep,
      String stableRevision,
      String canaryRevision,
      ReleasePlanStatus.GateDecision gateDecision,
      String reason,
      String message) {
    List<Condition> conditions =
        conditions(current, identity.generation(), phase, gateDecision, reason, message);
    return new ReleasePlanStatus(
        identity.generation(),
        identity.releaseId(),
        phase,
        currentStep,
        stableRevision,
        canaryRevision,
        phase == Phase.Bootstrapping
                || phase == Phase.Progressing
                || phase == Phase.Paused
                || phase == Phase.RollingBack
            ? new ReleasePlanStatus.ResourceReference(identity.name())
            : null,
        gateDecision,
        sameGeneration(current, identity.generation()) ? current.lastTransition() : null,
        sameGeneration(current, identity.generation()) ? current.pendingOutboxEvents() : null,
        conditions);
  }

  private List<Condition> conditions(
      ReleasePlanStatus current,
      long generation,
      Phase phase,
      ReleasePlanStatus.GateDecision gateDecision,
      String reason,
      String message) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(condition(current, "Ready", "False", generation, reason, message));
    conditions.add(
        condition(
            current,
            "Progressing",
            isProgressing(phase) ? "True" : "False",
            generation,
            reason,
            message));
    conditions.add(gateCondition(current, generation, gateDecision));
    conditions.add(
        condition(
            current,
            "AuditHealthy",
            "Unknown",
            generation,
            "AuditPipelinePending",
            "The durable audit pipeline is not implemented yet"));
    conditions.add(
        condition(
            current,
            "Degraded",
            phase == Phase.Failed
                    || phase == Phase.RollingBack
                    || "ArgoRolloutDegraded".equals(reason)
                    || "BootstrapGateInconclusive".equals(reason)
                ? "True"
                : "False",
            generation,
            reason,
            message));
    return List.copyOf(conditions);
  }

  private Condition gateCondition(
      ReleasePlanStatus current, long generation, ReleasePlanStatus.GateDecision gateDecision) {
    if (gateDecision == null) {
      return condition(
          current,
          "GateHealthy",
          "Unknown",
          generation,
          "AwaitingMetricEvidence",
          "No metric-gate decision has been observed");
    }
    String value =
        switch (gateDecision.decision()) {
          case "Successful" -> "True";
          case "Failed" -> "False";
          default -> "Unknown";
        };
    String reason =
        switch (gateDecision.decision()) {
          case "Successful" -> "GatePassed";
          case "Failed" -> "GateFailed";
          case "Error" -> "GateError";
          default -> "GateInconclusive";
        };
    return condition(
        current,
        "GateHealthy",
        value,
        generation,
        reason,
        "Argo reported " + gateDecision.decision() + " for " + gateDecision.evidenceRef());
  }

  private Condition condition(
      ReleasePlanStatus current,
      String type,
      String value,
      long generation,
      String reason,
      String message) {
    Condition existing = existingConditions(current).get(type);
    String transitionTime =
        existing != null
                && value.equals(existing.getStatus())
                && reason.equals(existing.getReason())
                && message.equals(existing.getMessage())
            ? existing.getLastTransitionTime()
            : Instant.now(clock).toString();
    return new ConditionBuilder()
        .withType(type)
        .withStatus(value)
        .withObservedGeneration(generation)
        .withReason(reason)
        .withMessage(message)
        .withLastTransitionTime(transitionTime)
        .build();
  }

  private static Map<String, Condition> existingConditions(ReleasePlanStatus status) {
    Map<String, Condition> byType = new HashMap<>();
    if (status != null && status.conditions() != null) {
      for (Condition condition : status.conditions()) {
        byType.put(condition.getType(), condition);
      }
    }
    return byType;
  }

  private static boolean isUnobservedGeneration(ReleasePlanStatus status, long generation) {
    return status == null
        || status.observedGeneration() == null
        || status.observedGeneration() != generation;
  }

  private static boolean sameGeneration(ReleasePlanStatus status, long generation) {
    return status != null
        && status.observedGeneration() != null
        && status.observedGeneration() == generation;
  }

  private static boolean isActive(Phase phase) {
    return phase == Phase.Validating
        || phase == Phase.Bootstrapping
        || phase == Phase.Progressing
        || phase == Phase.Paused
        || phase == Phase.RollingBack;
  }

  private static boolean isProgressing(Phase phase) {
    return phase == Phase.Validating
        || phase == Phase.Bootstrapping
        || phase == Phase.Progressing
        || phase == Phase.RollingBack;
  }
}
