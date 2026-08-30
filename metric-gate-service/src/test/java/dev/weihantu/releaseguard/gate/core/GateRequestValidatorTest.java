package dev.weihantu.releaseguard.gate.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.gate.GateTestFixtures;
import dev.weihantu.releaseguard.gate.api.GateEvaluationRequest;
import dev.weihantu.releaseguard.gate.api.InvalidEvaluationRequestException;
import org.junit.jupiter.api.Test;

class GateRequestValidatorTest {
  private final GateRequestValidator validator = new GateRequestValidator(new PromQueryRenderer());

  @Test
  void rejectsIdenticalStableAndCanaryRevisions() {
    GateEvaluationRequest source = GateTestFixtures.request();
    GateEvaluationRequest invalid =
        new GateEvaluationRequest(
            source.releaseId(),
            source.step(),
            source.mode(),
            source.namespace(),
            source.releaseName(),
            source.policy(),
            source.canaryRevision(),
            source.canaryRevision(),
            source.thresholds(),
            source.evaluationTime());

    InvalidEvaluationRequestException exception =
        assertThrows(InvalidEvaluationRequestException.class, () -> validator.validate(invalid));

    assertTrue(
        exception.violations().stream()
            .anyMatch(violation -> violation.path().equals("stableRevision")));
  }

  @Test
  void rejectsThresholdsOutsideAdministratorBounds() {
    GateEvaluationRequest source = GateTestFixtures.request();
    GateEvaluationRequest invalid =
        new GateEvaluationRequest(
            source.releaseId(),
            source.step(),
            source.mode(),
            source.namespace(),
            source.releaseName(),
            source.policy(),
            source.stableRevision(),
            source.canaryRevision(),
            new GateEvaluationRequest.Thresholds(
                null, 2.0, source.thresholds().maxDeltaFromStable()),
            source.evaluationTime());

    InvalidEvaluationRequestException exception =
        assertThrows(InvalidEvaluationRequestException.class, () -> validator.validate(invalid));

    assertTrue(
        exception.violations().stream()
            .anyMatch(violation -> violation.path().equals("thresholds.maxCanaryValue")));
  }

  @Test
  void rejectsUnsafeAdministratorQueryTemplates() {
    MetricPolicySpec sourcePolicy =
        GateTestFixtures.policy(MetricPolicySpec.Direction.LowerIsBetter);
    MetricPolicySpec unsafePolicy =
        new MetricPolicySpec(
            sourcePolicy.direction(),
            sourcePolicy.unit(),
            "sum(rate(requests{revision={{revision}}}[{{window}}]))",
            sourcePolicy.sampleCountQueryTemplate(),
            sourcePolicy.allowedThresholds(),
            sourcePolicy.minimumSamples(),
            sourcePolicy.window(),
            sourcePolicy.interval(),
            sourcePolicy.timeout());
    GateEvaluationRequest invalid = GateTestFixtures.request(unsafePolicy);

    InvalidEvaluationRequestException exception =
        assertThrows(InvalidEvaluationRequestException.class, () -> validator.validate(invalid));

    assertTrue(
        exception.violations().stream()
            .anyMatch(violation -> violation.path().equals("policy.spec.valueQueryTemplate")));
  }

  @Test
  void requiresAnAbsoluteThresholdForBootstrap() {
    GateEvaluationRequest source = GateTestFixtures.bootstrapRequest();
    GateEvaluationRequest invalid =
        new GateEvaluationRequest(
            source.releaseId(),
            source.step(),
            source.mode(),
            source.namespace(),
            source.releaseName(),
            source.policy(),
            source.stableRevision(),
            source.canaryRevision(),
            new GateEvaluationRequest.Thresholds(null, null, 0.01),
            source.evaluationTime());

    InvalidEvaluationRequestException exception =
        assertThrows(InvalidEvaluationRequestException.class, () -> validator.validate(invalid));

    assertTrue(
        exception.violations().stream()
            .anyMatch(violation -> violation.path().equals("thresholds")));
  }
}
