package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidationContext;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidator;
import dev.weihantu.releaseguard.api.validation.ValidationError;
import dev.weihantu.releaseguard.api.validation.ValidationResult;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
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
              next == Phase.Bootstrapping ? "BootstrapPrepared" : "RolloutPrepared",
              "Desired rollout resources are prepared and metric-gated progression is pending");
      return new ReconciliationPlan(status, renderer.render(plan, acceptedPolicies), null);
    }

    if (currentPhase == Phase.Bootstrapping || currentPhase == Phase.Progressing) {
      return activeRelease(
          plan, current, identity, currentPhase, acceptedPolicies, rolloutObservation);
    }

    if (currentPhase == Phase.Paused) {
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
    Phase phase = projectedPhase(currentPhase, observation);
    phaseMachine.requireTransition(currentPhase, phase);
    String stableRevision = current.stableRevision();
    String canaryRevision = current.canaryRevision();
    Integer currentStep = current.currentStep();
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
      currentStep =
          logicalStep(observation.currentStepIndex(), plan.getSpec().rollout().steps().size());
      reason = observationReason(currentPhase, observation);
      message = observationMessage(currentPhase, observation);
    }

    ReleasePlanStatus status =
        status(
            current, identity, phase, currentStep, stableRevision, canaryRevision, reason, message);
    return new ReconciliationPlan(status, renderer.render(plan, acceptedPolicies), null);
  }

  private static Map<String, MetricPolicySpec> policySpecs(
      Map<String, MetricPolicySnapshot> snapshots) {
    Map<String, MetricPolicySpec> specs = new HashMap<>();
    snapshots.forEach((name, snapshot) -> specs.put(name, snapshot.spec()));
    return Map.copyOf(specs);
  }

  private static Phase projectedPhase(Phase currentPhase, RolloutObservation observation) {
    if (!observation.present() || !observation.statusCurrent()) {
      return currentPhase;
    }
    if ("Degraded".equals(observation.argoPhase())) {
      return currentPhase == Phase.Bootstrapping ? Phase.Failed : currentPhase;
    }
    if (currentPhase == Phase.Bootstrapping) {
      return currentPhase;
    }
    boolean paused =
        "Paused".equals(observation.argoPhase()) || "Suspended".equals(observation.argoPhase());
    if (paused) {
      return Phase.Paused;
    }
    return currentPhase;
  }

  private static String observationReason(Phase currentPhase, RolloutObservation observation) {
    if (currentPhase == Phase.Bootstrapping && "Healthy".equals(observation.argoPhase())) {
      return "BootstrapReadyAwaitingGate";
    }
    if ("Degraded".equals(observation.argoPhase())) {
      return "ArgoRolloutDegraded";
    }
    if ("Paused".equals(observation.argoPhase()) || "Suspended".equals(observation.argoPhase())) {
      return "ArgoRolloutPaused";
    }
    return "ArgoRolloutObserved";
  }

  private static String observationMessage(Phase currentPhase, RolloutObservation observation) {
    if (currentPhase == Phase.Bootstrapping && "Healthy".equals(observation.argoPhase())) {
      return "Bootstrap workload is ready; administrator-approved metric evidence is still required";
    }
    return "Observed Argo Rollout phase " + observation.argoPhase();
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

  private static Integer logicalStep(Integer argoStepIndex, int releaseStepCount) {
    if (releaseStepCount == 0) {
      return null;
    }
    if (argoStepIndex == null) {
      return releaseStepCount - 1;
    }
    return Math.min(argoStepIndex / 2, releaseStepCount - 1);
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
      String reason,
      String message) {
    List<Condition> conditions = conditions(current, identity.generation(), phase, reason, message);
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
        sameGeneration(current, identity.generation()) ? current.lastGateDecision() : null,
        sameGeneration(current, identity.generation()) ? current.lastTransition() : null,
        sameGeneration(current, identity.generation()) ? current.pendingOutboxEvents() : null,
        conditions);
  }

  private List<Condition> conditions(
      ReleasePlanStatus current, long generation, Phase phase, String reason, String message) {
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
    conditions.add(
        condition(
            current,
            "GateHealthy",
            "Unknown",
            generation,
            "AwaitingMetricEvidence",
            "No metric-gate decision has been observed"));
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
                ? "True"
                : "False",
            generation,
            reason,
            message));
    return List.copyOf(conditions);
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
