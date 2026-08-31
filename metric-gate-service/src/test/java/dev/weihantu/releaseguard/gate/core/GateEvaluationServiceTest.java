package dev.weihantu.releaseguard.gate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.gate.GateTestFixtures;
import dev.weihantu.releaseguard.gate.api.GateEvaluationRequest;
import dev.weihantu.releaseguard.gate.api.GateEvaluationResponse;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusFailure;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusObservation;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusQueryClient;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusQueryException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GateEvaluationServiceTest {
  @Test
  void passesLowerIsBetterEvidenceAndProducesAStableDigest() {
    PrometheusQueryClient prometheus =
        observations(
            Map.of(
                GateTestFixtures.CANARY_VALUE_QUERY, 0.004,
                GateTestFixtures.CANARY_SAMPLES_QUERY, 180.0,
                GateTestFixtures.STABLE_VALUE_QUERY, 0.002,
                GateTestFixtures.STABLE_SAMPLES_QUERY, 1200.0));
    GateEvaluationService service = GateTestFixtures.service(prometheus);

    GateEvaluationResponse first = service.evaluate(GateTestFixtures.request());
    GateEvaluationResponse second = service.evaluate(GateTestFixtures.request());

    assertEquals(GateEvaluationResponse.Decision.Pass, first.decision());
    assertEquals("WithinAbsoluteAndBaselineLimits", first.reason());
    assertEquals(0.004, first.canaryValue());
    assertEquals(0.002, first.stableValue());
    assertEquals(4, first.observations().size());
    assertEquals(first.evidenceDigest(), second.evidenceDigest());
    assertEquals(
        "sha256:06c2c2660503b29f8f273bc6a737159761b3445e09d1015c50b41b465929557b",
        first.evidenceDigest());
    assertTrue(first.evidenceDigest().matches("sha256:[0-9a-f]{64}"));
  }

  @Test
  void assignsServerTimeWhenTheCallerOmitsEvaluationTime() {
    GateEvaluationRequest source = GateTestFixtures.request();
    GateEvaluationRequest withoutEvaluationTime =
        new GateEvaluationRequest(
            source.releaseId(),
            source.step(),
            source.mode(),
            source.namespace(),
            source.releaseName(),
            source.policy(),
            source.stableRevision(),
            source.canaryRevision(),
            source.thresholds(),
            null);
    GateEvaluationService service =
        GateTestFixtures.service(
            observations(
                Map.of(
                    GateTestFixtures.CANARY_VALUE_QUERY, 0.004,
                    GateTestFixtures.CANARY_SAMPLES_QUERY, 180.0,
                    GateTestFixtures.STABLE_VALUE_QUERY, 0.002,
                    GateTestFixtures.STABLE_SAMPLES_QUERY, 1200.0)));

    GateEvaluationResponse response = service.evaluate(withoutEvaluationTime);

    assertEquals(GateEvaluationResponse.Decision.Pass, response.decision());
    assertEquals(GateTestFixtures.EVALUATION_TIME, response.windowEnd());
  }

  @Test
  void returnsFailForACompletedThresholdBreach() {
    GateEvaluationService service =
        GateTestFixtures.service(
            observations(
                Map.of(
                    GateTestFixtures.CANARY_VALUE_QUERY, 0.20,
                    GateTestFixtures.CANARY_SAMPLES_QUERY, 500.0,
                    GateTestFixtures.STABLE_VALUE_QUERY, 0.01,
                    GateTestFixtures.STABLE_SAMPLES_QUERY, 1000.0)));

    GateEvaluationResponse response = service.evaluate(GateTestFixtures.request());

    assertEquals(GateEvaluationResponse.Decision.Fail, response.decision());
    assertEquals("AbsoluteAndBaselineThresholdsBreached", response.reason());
  }

  @Test
  void returnsInconclusiveWhenEitherRevisionHasTooFewSamples() {
    GateEvaluationService service =
        GateTestFixtures.service(
            observations(
                Map.of(
                    GateTestFixtures.CANARY_VALUE_QUERY, 0.004,
                    GateTestFixtures.CANARY_SAMPLES_QUERY, 99.0,
                    GateTestFixtures.STABLE_VALUE_QUERY, 0.002,
                    GateTestFixtures.STABLE_SAMPLES_QUERY, 1200.0)));

    GateEvaluationResponse response = service.evaluate(GateTestFixtures.request());

    assertEquals(GateEvaluationResponse.Decision.Inconclusive, response.decision());
    assertEquals("InsufficientSamples", response.reason());
  }

  @ParameterizedTest
  @EnumSource(PrometheusFailure.class)
  void mapsEveryProviderFailureToInconclusive(PrometheusFailure failure) {
    PrometheusQueryClient prometheus =
        (query, evaluationTime, timeout) -> {
          throw new PrometheusQueryException(failure);
        };

    GateEvaluationResponse response =
        GateTestFixtures.service(prometheus).evaluate(GateTestFixtures.request());

    assertEquals(GateEvaluationResponse.Decision.Inconclusive, response.decision());
    assertEquals(failure.reason(), response.reason());
    assertEquals(failure.reason(), response.observations().getFirst().outcome());
  }

  @Test
  void rejectsStaleObservationsWithoutQueryingFurther() {
    PrometheusQueryClient stale =
        (query, evaluationTime, timeout) ->
            new PrometheusObservation(
                query,
                GateTestFixtures.EVALUATION_TIME.minus(Duration.ofMinutes(3)),
                0.004,
                Map.of());

    GateEvaluationResponse response =
        GateTestFixtures.service(stale).evaluate(GateTestFixtures.request());

    assertEquals(GateEvaluationResponse.Decision.Inconclusive, response.decision());
    assertEquals("StaleObservation", response.reason());
    assertEquals(1, response.observations().size());
  }

  @Test
  void bootstrapUsesOnlyCanaryEvidenceAndIgnoresTheBaselineDelta() {
    Map<String, Double> values =
        Map.of(
            GateTestFixtures.CANARY_VALUE_QUERY, 0.004,
            GateTestFixtures.CANARY_SAMPLES_QUERY, 180.0);

    GateEvaluationResponse response =
        GateTestFixtures.service(observations(values))
            .evaluate(GateTestFixtures.bootstrapRequest());

    assertEquals(GateEvaluationResponse.Decision.Pass, response.decision());
    assertEquals("WithinAbsoluteLimit", response.reason());
    assertEquals(2, response.observations().size());
    assertNull(response.stableValue());
    assertNull(response.stableSamples());
  }

  @Test
  void appliesHigherIsBetterAbsoluteAndBaselinePredicates() {
    MetricPolicySpec policy = GateTestFixtures.policy(MetricPolicySpec.Direction.HigherIsBetter);
    GateEvaluationRequest source = GateTestFixtures.request(policy);
    GateEvaluationRequest request =
        new GateEvaluationRequest(
            source.releaseId(),
            source.step(),
            source.mode(),
            source.namespace(),
            source.releaseName(),
            source.policy(),
            source.stableRevision(),
            source.canaryRevision(),
            new GateEvaluationRequest.Thresholds(0.90, null, 0.10),
            source.evaluationTime());
    GateEvaluationService service =
        GateTestFixtures.service(
            observations(
                Map.of(
                    GateTestFixtures.CANARY_VALUE_QUERY, 0.95,
                    GateTestFixtures.CANARY_SAMPLES_QUERY, 180.0,
                    GateTestFixtures.STABLE_VALUE_QUERY, 1.0,
                    GateTestFixtures.STABLE_SAMPLES_QUERY, 1200.0)));

    GateEvaluationResponse response = service.evaluate(request);

    assertEquals(GateEvaluationResponse.Decision.Pass, response.decision());
  }

  private static PrometheusQueryClient observations(Map<String, Double> values) {
    Map<String, Double> ordered = new LinkedHashMap<>(values);
    return (query, evaluationTime, timeout) -> {
      Double value = ordered.get(query);
      if (value == null) {
        throw new AssertionError("unexpected query: " + query);
      }
      return GateTestFixtures.observation(query, value);
    };
  }
}
