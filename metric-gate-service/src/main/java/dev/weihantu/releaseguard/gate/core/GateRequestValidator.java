package dev.weihantu.releaseguard.gate.core;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.validation.MetricPolicyValidator;
import dev.weihantu.releaseguard.api.validation.ValidationError;
import dev.weihantu.releaseguard.gate.api.GateEvaluationRequest;
import dev.weihantu.releaseguard.gate.api.InvalidEvaluationRequestException;
import dev.weihantu.releaseguard.gate.api.InvalidEvaluationRequestException.Violation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GateRequestValidator {
  private final MetricPolicyValidator policyValidator = new MetricPolicyValidator();
  private final PromQueryRenderer queryRenderer;

  public GateRequestValidator(PromQueryRenderer queryRenderer) {
    this.queryRenderer = queryRenderer;
  }

  public void validate(GateEvaluationRequest request) {
    List<Violation> violations = new ArrayList<>();
    if (request == null) {
      throw new InvalidEvaluationRequestException(
          List.of(new Violation("request", "request body is required")));
    }
    required(request.releaseId(), "releaseId", violations);
    if (request.step() == null || request.step() < 0) {
      violations.add(new Violation("step", "step must be zero or greater"));
    }
    if (request.mode() == null) {
      violations.add(new Violation("mode", "mode is required"));
    }
    required(request.namespace(), "namespace", violations);
    required(request.releaseName(), "releaseName", violations);
    required(request.canaryRevision(), "canaryRevision", violations);
    if (request.evaluationTime() == null) {
      violations.add(new Violation("evaluationTime", "evaluationTime is required"));
    }
    validateRevisions(request, violations);
    validatePolicy(request, violations);
    validateThresholds(request, violations);
    if (!violations.isEmpty()) {
      violations.sort(Comparator.comparing(Violation::path).thenComparing(Violation::message));
      throw new InvalidEvaluationRequestException(violations);
    }
  }

  private void validatePolicy(GateEvaluationRequest request, List<Violation> violations) {
    GateEvaluationRequest.PolicySnapshot policy = request.policy();
    if (policy == null) {
      violations.add(new Violation("policy", "policy snapshot is required"));
      return;
    }
    required(policy.name(), "policy.name", violations);
    required(policy.resourceVersion(), "policy.resourceVersion", violations);
    List<ValidationError> policyErrors = policyValidator.validate(policy.spec()).errors();
    for (ValidationError error : policyErrors) {
      violations.add(
          new Violation("policy." + error.path(), error.requirement() + ": " + error.message()));
    }
    if (policyErrors.isEmpty()) {
      validateTemplateSafety(request, violations);
    }
  }

  private void validateTemplateSafety(GateEvaluationRequest request, List<Violation> violations) {
    MetricPolicySpec spec = request.policy().spec();
    String revision = longer(request.canaryRevision(), request.stableRevision());
    Map<String, String> substitutions =
        Map.of(
            "revision", fallback(revision, "revision-value"),
            "namespace", fallback(request.namespace(), "namespace-value"),
            "releaseName", fallback(request.releaseName(), "release-name"),
            "window", spec.window());
    safeRender(
        spec.valueQueryTemplate(), "policy.spec.valueQueryTemplate", substitutions, violations);
    safeRender(
        spec.sampleCountQueryTemplate(),
        "policy.spec.sampleCountQueryTemplate",
        substitutions,
        violations);
  }

  private static String fallback(String value, String fallback) {
    return value == null ? fallback : value;
  }

  private static String longer(String first, String second) {
    if (first == null) {
      return second;
    }
    return second != null && second.length() > first.length() ? second : first;
  }

  private void safeRender(
      String template, String path, Map<String, String> substitutions, List<Violation> violations) {
    try {
      queryRenderer.render(template, substitutions);
    } catch (IllegalArgumentException exception) {
      violations.add(new Violation(path, exception.getMessage()));
    }
  }

  private static void validateRevisions(GateEvaluationRequest request, List<Violation> violations) {
    if (request.mode() == GateEvaluationRequest.Mode.Canary) {
      required(request.stableRevision(), "stableRevision", violations);
      if (request.stableRevision() != null
          && request.stableRevision().equals(request.canaryRevision())) {
        violations.add(
            new Violation("stableRevision", "stable and canary revisions must be different"));
      }
    } else if (request.mode() == GateEvaluationRequest.Mode.Bootstrap
        && request.stableRevision() != null
        && !request.stableRevision().isBlank()) {
      violations.add(
          new Violation("stableRevision", "bootstrap evaluations must not provide a baseline"));
    }
  }

  private static void validateThresholds(
      GateEvaluationRequest request, List<Violation> violations) {
    GateEvaluationRequest.Thresholds thresholds = request.thresholds();
    if (thresholds == null) {
      violations.add(new Violation("thresholds", "thresholds are required"));
      return;
    }
    finite(thresholds.minCanaryValue(), "thresholds.minCanaryValue", violations);
    finite(thresholds.maxCanaryValue(), "thresholds.maxCanaryValue", violations);
    finite(thresholds.maxDeltaFromStable(), "thresholds.maxDeltaFromStable", violations);
    MetricPolicySpec spec = request.policy() == null ? null : request.policy().spec();
    MetricPolicySpec.Direction direction = spec == null ? null : spec.direction();
    if (direction == MetricPolicySpec.Direction.LowerIsBetter
        && thresholds.minCanaryValue() != null) {
      violations.add(
          new Violation("thresholds.minCanaryValue", "LowerIsBetter policies use maxCanaryValue"));
    }
    if (direction == MetricPolicySpec.Direction.HigherIsBetter
        && thresholds.maxCanaryValue() != null) {
      violations.add(
          new Violation("thresholds.maxCanaryValue", "HigherIsBetter policies use minCanaryValue"));
    }
    Double absolute =
        direction == MetricPolicySpec.Direction.HigherIsBetter
            ? thresholds.minCanaryValue()
            : thresholds.maxCanaryValue();
    if (request.mode() == GateEvaluationRequest.Mode.Bootstrap && absolute == null) {
      violations.add(
          new Violation(
              "thresholds", "bootstrap evaluations require an absolute canary threshold"));
    }
    if (spec == null || spec.allowedThresholds() == null) {
      return;
    }
    within(
        absolute,
        spec.allowedThresholds().canary(),
        direction == MetricPolicySpec.Direction.HigherIsBetter
            ? "thresholds.minCanaryValue"
            : "thresholds.maxCanaryValue",
        violations);
    within(
        thresholds.maxDeltaFromStable(),
        spec.allowedThresholds().stableDelta(),
        "thresholds.maxDeltaFromStable",
        violations);
  }

  private static void finite(Double value, String path, List<Violation> violations) {
    if (value != null && !Double.isFinite(value)) {
      violations.add(new Violation(path, "threshold must be finite"));
    }
  }

  private static void within(
      Double value, MetricPolicySpec.NumericRange range, String path, List<Violation> violations) {
    if (value == null) {
      return;
    }
    if (range == null
        || range.minimum() == null
        || range.maximum() == null
        || value < range.minimum()
        || value > range.maximum()) {
      violations.add(new Violation(path, "threshold is outside administrator-approved bounds"));
    }
  }

  private static void required(String value, String path, List<Violation> violations) {
    if (value == null || value.isBlank()) {
      violations.add(new Violation(path, "value is required"));
    } else if (value.length() > 512) {
      violations.add(new Violation(path, "value must not exceed 512 characters"));
    }
  }
}
