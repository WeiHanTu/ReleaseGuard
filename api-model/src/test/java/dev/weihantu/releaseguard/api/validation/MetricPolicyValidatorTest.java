package dev.weihantu.releaseguard.api.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import org.junit.jupiter.api.Test;

class MetricPolicyValidatorTest {
  private final MetricPolicyValidator validator = new MetricPolicyValidator();

  @Test
  void acceptsValidPolicy() {
    assertTrue(validator.validate(TestFixtures.policy()).isValid());
  }

  @Test
  void rejectsForbiddenAndMalformedTemplateSubstitutions() {
    MetricPolicySpec invalid =
        copyWithQueries(
            "sum(rate(requests{pod=\"{{pod}}\"}[{{window}}]))",
            "sum(increase(requests{revision=\"{{revision}\"}[{{window}}]))");

    ValidationResult result = validator.validate(invalid);

    assertTrue(result.hasRequirement("RG-POL-001"));
    assertTrue(result.hasRequirement("RG-POL-002"));
    assertTrue(result.hasRequirement("RG-POL-004"));
  }

  @Test
  void rejectsUnorderedThresholdBounds() {
    MetricPolicySpec source = TestFixtures.policy();
    MetricPolicySpec invalid =
        new MetricPolicySpec(
            source.direction(),
            source.unit(),
            source.valueQueryTemplate(),
            source.sampleCountQueryTemplate(),
            new MetricPolicySpec.AllowedThresholds(
                new MetricPolicySpec.NumericRange(1.0, 0.0),
                source.allowedThresholds().stableDelta()),
            source.minimumSamples(),
            source.window(),
            source.interval(),
            source.timeout());

    assertTrue(validator.validate(invalid).hasRequirement("RG-POL-006"));
  }

  @Test
  void rejectsTimeoutLongerThanInterval() {
    MetricPolicySpec source = TestFixtures.policy();
    MetricPolicySpec invalid =
        new MetricPolicySpec(
            source.direction(),
            source.unit(),
            source.valueQueryTemplate(),
            source.sampleCountQueryTemplate(),
            source.allowedThresholds(),
            source.minimumSamples(),
            "2m",
            "5s",
            "20s");

    assertTrue(validator.validate(invalid).hasRequirement("RG-POL-004"));
  }

  private static MetricPolicySpec copyWithQueries(String valueQuery, String sampleQuery) {
    MetricPolicySpec source = TestFixtures.policy();
    return new MetricPolicySpec(
        source.direction(),
        source.unit(),
        valueQuery,
        sampleQuery,
        source.allowedThresholds(),
        source.minimumSamples(),
        source.window(),
        source.interval(),
        source.timeout());
  }
}
