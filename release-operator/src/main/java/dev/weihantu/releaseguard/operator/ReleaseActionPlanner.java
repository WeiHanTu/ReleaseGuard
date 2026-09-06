package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseAction;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionStatus.Phase;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.api.validation.ReleaseActionValidator;
import dev.weihantu.releaseguard.api.validation.ValidationError;
import dev.weihantu.releaseguard.api.validation.ValidationResult;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class ReleaseActionPlanner {
  private final ReleaseActionValidator validator;
  private final Clock clock;

  public ReleaseActionPlanner() {
    this(new ReleaseActionValidator(), Clock.systemUTC());
  }

  ReleaseActionPlanner(ReleaseActionValidator validator, Clock clock) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public record Result(ReleaseActionStatus status, Effect effect) {}

  public enum Effect {
    None,
    PauseRollout,
    PromoteRollout,
    AbortRollout,
    AbortBootstrap,
    RetryAnalysis
  }

  public Result plan(ReleaseAction action, ReleasePlan plan) {
    return plan(action, plan, List.of());
  }

  public Result plan(ReleaseAction action, ReleasePlan plan, List<ReleaseAction> existingActions) {
    if (action.getStatus() != null && isTerminal(action.getStatus().phase())) {
      return new Result(action.getStatus(), Effect.None);
    }

    ValidationResult validation = validator.validate(action.getSpec());
    if (!validation.isValid()) {
      ValidationError error = validation.errors().getFirst();
      return result(
          action,
          Phase.Rejected,
          error.requirement(),
          error.path() + ": " + error.message(),
          Effect.None);
    }

    if (existingActions != null && action.getSpec() != null) {
      String idempotencyKey = action.getSpec().idempotencyKey();
      for (ReleaseAction existing : existingActions) {
        if (existing == null
            || existing.getMetadata() == null
            || Objects.equals(existing.getMetadata().getName(), action.getMetadata().getName())) {
          continue;
        }
        if (existing.getSpec() != null
            && Objects.equals(idempotencyKey, existing.getSpec().idempotencyKey())) {
          if (sameRequest(action.getSpec(), existing.getSpec())) {
            if (existing.getStatus() != null) {
              return new Result(existing.getStatus(), Effect.None);
            }
          } else {
            return result(
                action,
                Phase.Rejected,
                "IdempotencyConflict",
                "Idempotency key '"
                    + idempotencyKey
                    + "' was already used with different request content",
                Effect.None);
          }
        }
      }
    }

    ReleaseActionSpec.Target target = action.getSpec().target();
    if (plan == null) {
      return result(
          action,
          Phase.Rejected,
          "TargetPlanNotFound",
          "Target ReleasePlan " + target.name() + " was not found",
          Effect.None);
    }

    if (!Objects.equals(target.uid(), plan.getMetadata().getUid())) {
      return result(
          action,
          Phase.Rejected,
          "StaleTargetUid",
          "Target UID "
              + target.uid()
              + " does not match live ReleasePlan UID "
              + plan.getMetadata().getUid(),
          Effect.None);
    }

    if (!Objects.equals(target.generation(), plan.getMetadata().getGeneration())) {
      return result(
          action,
          Phase.Rejected,
          "StaleTargetGeneration",
          "Target generation "
              + target.generation()
              + " does not match live ReleasePlan generation "
              + plan.getMetadata().getGeneration(),
          Effect.None);
    }

    ReleasePlanStatus planStatus = plan.getStatus();
    if (planStatus == null || planStatus.phase() == null) {
      return result(
          action,
          Phase.Rejected,
          "TargetPlanNotObserved",
          "Target ReleasePlan has not been observed by the operator",
          Effect.None);
    }

    ReleasePlanStatus.Phase currentPhase = planStatus.phase();
    if (currentPhase == ReleasePlanStatus.Phase.Succeeded
        || currentPhase == ReleasePlanStatus.Phase.RolledBack
        || currentPhase == ReleasePlanStatus.Phase.Failed
        || currentPhase == ReleasePlanStatus.Phase.Deleting) {
      return result(
          action,
          Phase.Rejected,
          "TargetReleaseTerminal",
          "Release is already terminal in phase " + currentPhase,
          Effect.None);
    }

    return switch (action.getSpec().type()) {
      case Pause -> {
        if (currentPhase != ReleasePlanStatus.Phase.Bootstrapping
            && currentPhase != ReleasePlanStatus.Phase.Progressing) {
          yield result(
              action,
              Phase.Rejected,
              "InvalidSourcePhase",
              "Pause is only permitted from Bootstrapping or Progressing, but release is in "
                  + currentPhase,
              Effect.None);
        }
        yield result(
            action,
            Phase.Completed,
            "ActionExecuted",
            "Pause requested successfully",
            Effect.PauseRollout);
      }
      case Promote -> {
        if (currentPhase != ReleasePlanStatus.Phase.Paused) {
          yield result(
              action,
              Phase.Rejected,
              "InvalidSourcePhase",
              "Promote is only permitted from Paused, but release is in " + currentPhase,
              Effect.None);
        }
        ReleasePlanStatus.GateDecision gateDecision = planStatus.lastGateDecision();
        if (gateDecision == null || !"Successful".equals(gateDecision.decision())) {
          yield result(
              action,
              Phase.Rejected,
              "GateEvidenceNotPassed",
              "Promote requires the last gate decision to be Successful, but it was "
                  + (gateDecision == null ? "none" : gateDecision.decision()),
              Effect.None);
        }
        yield result(
            action,
            Phase.Completed,
            "ActionExecuted",
            "Promote requested successfully",
            Effect.PromoteRollout);
      }
      case Abort -> {
        if (currentPhase == ReleasePlanStatus.Phase.Bootstrapping) {
          yield result(
              action,
              Phase.Completed,
              "ActionExecuted",
              "Abort requested; stopping bootstrap candidate traffic",
              Effect.AbortBootstrap);
        }
        yield result(
            action,
            Phase.Completed,
            "ActionExecuted",
            "Abort requested; initiating rollback",
            Effect.AbortRollout);
      }
      case RetryAnalysis -> {
        if (currentPhase != ReleasePlanStatus.Phase.Paused) {
          yield result(
              action,
              Phase.Rejected,
              "InvalidSourcePhase",
              "RetryAnalysis is only permitted from Paused, but release is in " + currentPhase,
              Effect.None);
        }
        yield result(
            action,
            Phase.Completed,
            "ActionExecuted",
            "RetryAnalysis requested successfully",
            Effect.RetryAnalysis);
      }
    };
  }

  private Result result(
      ReleaseAction action, Phase phase, String reason, String message, Effect effect) {
    String completedAt = Instant.now(clock).toString();
    String digest = digest(action, phase, reason);
    List<Condition> conditions =
        List.of(
            new ConditionBuilder()
                .withType("Ready")
                .withStatus(phase == Phase.Completed ? "True" : "False")
                .withReason(reason)
                .withMessage(message)
                .withLastTransitionTime(completedAt)
                .build(),
            new ConditionBuilder()
                .withType("Completed")
                .withStatus(phase == Phase.Completed ? "True" : "False")
                .withReason(reason)
                .withMessage(message)
                .withLastTransitionTime(completedAt)
                .build());
    ReleaseActionStatus status =
        new ReleaseActionStatus(phase, reason, completedAt, digest, conditions);
    return new Result(status, effect);
  }

  private static String digest(ReleaseAction action, Phase phase, String reason) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      String input =
          action.getMetadata().getName()
              + ":"
              + action.getSpec().type()
              + ":"
              + action.getSpec().target().uid()
              + ":"
              + action.getSpec().target().generation()
              + ":"
              + phase
              + ":"
              + reason;
      return "sha256:"
          + HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static boolean sameRequest(ReleaseActionSpec a, ReleaseActionSpec b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    return Objects.equals(a.type(), b.type())
        && Objects.equals(a.target(), b.target())
        && Objects.equals(a.reason(), b.reason())
        && Objects.equals(a.actor(), b.actor());
  }

  private static boolean isTerminal(Phase phase) {
    return phase == Phase.Completed || phase == Phase.Rejected || phase == Phase.Failed;
  }
}
