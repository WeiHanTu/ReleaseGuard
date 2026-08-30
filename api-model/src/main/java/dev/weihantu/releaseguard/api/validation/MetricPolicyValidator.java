package dev.weihantu.releaseguard.api.validation;

import dev.weihantu.releaseguard.api.v1alpha1.KubernetesDuration;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetricPolicyValidator {
  private static final Pattern SUBSTITUTION =
      Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*}}", Pattern.MULTILINE);
  private static final Set<String> ALLOWED_SUBSTITUTIONS =
      Set.of("revision", "namespace", "releaseName", "window");
  private static final Set<String> REQUIRED_SUBSTITUTIONS = Set.of("revision", "window");

  public ValidationResult validate(MetricPolicySpec spec) {
    List<ValidationError> errors = new ArrayList<>();
    if (spec == null) {
      add(errors, "RG-POL-003", "spec", "spec is required");
      return ValidationResult.from(errors);
    }
    if (spec.direction() == null) {
      add(errors, "RG-POL-003", "spec.direction", "direction is required");
    }
    validateTemplate(spec.valueQueryTemplate(), "spec.valueQueryTemplate", errors);
    validateTemplate(spec.sampleCountQueryTemplate(), "spec.sampleCountQueryTemplate", errors);
    validateThresholds(spec.allowedThresholds(), errors);
    if (spec.minimumSamples() == null || spec.minimumSamples() < 1) {
      add(errors, "RG-POL-004", "spec.minimumSamples", "minimumSamples must be positive");
    }

    Duration window = parseDuration(spec.window(), "spec.window", errors);
    Duration interval = parseDuration(spec.interval(), "spec.interval", errors);
    Duration timeout = parseDuration(spec.timeout(), "spec.timeout", errors);
    if (window != null && interval != null && interval.compareTo(window) > 0) {
      add(errors, "RG-POL-004", "spec.interval", "interval must not exceed the evaluation window");
    }
    if (interval != null && timeout != null && timeout.compareTo(interval) > 0) {
      add(errors, "RG-POL-004", "spec.timeout", "provider timeout must not exceed the interval");
    }
    return ValidationResult.from(errors);
  }

  private static void validateTemplate(String template, String path, List<ValidationError> errors) {
    if (template == null || template.isBlank()) {
      add(errors, "RG-POL-004", path, "query template is required");
      return;
    }
    Matcher matcher = SUBSTITUTION.matcher(template);
    Set<String> substitutions = new HashSet<>();
    StringBuilder unmatched = new StringBuilder();
    while (matcher.find()) {
      String substitution = matcher.group(1);
      substitutions.add(substitution);
      boolean quoted = insideQuotedString(template, matcher.start());
      if ("window".equals(substitution) && quoted) {
        add(errors, "RG-POL-001", path, "window substitution must not be quoted");
      }
      if (!"window".equals(substitution) && !quoted) {
        add(
            errors,
            "RG-POL-001",
            path,
            substitution + " substitution must be inside a string literal");
      }
      matcher.appendReplacement(unmatched, "");
    }
    matcher.appendTail(unmatched);
    if (unmatched.indexOf("{{") >= 0 || unmatched.indexOf("}}") >= 0) {
      add(errors, "RG-POL-001", path, "query template contains a malformed substitution");
    }
    if (!ALLOWED_SUBSTITUTIONS.containsAll(substitutions)) {
      Set<String> forbidden = new HashSet<>(substitutions);
      forbidden.removeAll(ALLOWED_SUBSTITUTIONS);
      add(errors, "RG-POL-002", path, "query template uses forbidden substitutions: " + forbidden);
    }
    if (!substitutions.containsAll(REQUIRED_SUBSTITUTIONS)) {
      add(errors, "RG-POL-004", path, "query template must use revision and window substitutions");
    }
  }

  private static boolean insideQuotedString(String value, int endExclusive) {
    boolean quoted = false;
    for (int index = 0; index < endExclusive; index++) {
      if (value.charAt(index) == '"' && !escaped(value, index)) {
        quoted = !quoted;
      }
    }
    return quoted;
  }

  private static boolean escaped(String value, int index) {
    int backslashes = 0;
    for (int cursor = index - 1; cursor >= 0 && value.charAt(cursor) == '\\'; cursor--) {
      backslashes++;
    }
    return backslashes % 2 == 1;
  }

  private static void validateThresholds(
      MetricPolicySpec.AllowedThresholds thresholds, List<ValidationError> errors) {
    if (thresholds == null || !validRange(thresholds.canary())) {
      add(
          errors,
          "RG-POL-006",
          "spec.allowedThresholds.canary",
          "canary threshold bounds must be finite and ordered");
    }
    if (thresholds != null
        && thresholds.stableDelta() != null
        && !validRange(thresholds.stableDelta())) {
      add(
          errors,
          "RG-POL-006",
          "spec.allowedThresholds.stableDelta",
          "stable-delta threshold bounds must be finite and ordered");
    }
  }

  private static boolean validRange(MetricPolicySpec.NumericRange range) {
    return range != null
        && range.minimum() != null
        && range.maximum() != null
        && Double.isFinite(range.minimum())
        && Double.isFinite(range.maximum())
        && range.minimum() <= range.maximum();
  }

  private static Duration parseDuration(String value, String path, List<ValidationError> errors) {
    var parsed = KubernetesDuration.parse(value);
    if (parsed.isEmpty()) {
      add(errors, "RG-POL-004", path, "value must be a positive Kubernetes duration");
      return null;
    }
    return parsed.get();
  }

  private static void add(
      List<ValidationError> errors, String requirement, String path, String message) {
    errors.add(new ValidationError(requirement, path, message));
  }
}
