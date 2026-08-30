package dev.weihantu.releaseguard.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.gate.api.GateEvaluationRequest;
import dev.weihantu.releaseguard.gate.config.GateProperties;
import dev.weihantu.releaseguard.gate.core.EvidenceDigester;
import dev.weihantu.releaseguard.gate.core.GateEvaluationService;
import dev.weihantu.releaseguard.gate.core.GateRequestValidator;
import dev.weihantu.releaseguard.gate.core.PromQueryRenderer;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusObservation;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusQueryClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public final class GateTestFixtures {
  public static final Instant EVALUATION_TIME = Instant.parse("2026-08-26T21:00:00Z");
  public static final String CANARY_VALUE_QUERY =
      "sum(rate(requests{revision=\"checkout-canary\"}[2m]))";
  public static final String CANARY_SAMPLES_QUERY =
      "sum(increase(requests{revision=\"checkout-canary\"}[2m]))";
  public static final String STABLE_VALUE_QUERY =
      "sum(rate(requests{revision=\"checkout-stable\"}[2m]))";
  public static final String STABLE_SAMPLES_QUERY =
      "sum(increase(requests{revision=\"checkout-stable\"}[2m]))";

  private GateTestFixtures() {}

  public static GateEvaluationRequest request() {
    return request(policy(MetricPolicySpec.Direction.LowerIsBetter));
  }

  public static GateEvaluationRequest request(MetricPolicySpec policy) {
    return new GateEvaluationRequest(
        "release-uid:4",
        1,
        GateEvaluationRequest.Mode.Canary,
        "demo",
        "checkout",
        new GateEvaluationRequest.PolicySnapshot("http-errors", "817263", policy),
        "checkout-stable",
        "checkout-canary",
        new GateEvaluationRequest.Thresholds(null, 0.01, 0.005),
        EVALUATION_TIME);
  }

  public static GateEvaluationRequest bootstrapRequest() {
    GateEvaluationRequest source = request();
    return new GateEvaluationRequest(
        source.releaseId(),
        0,
        GateEvaluationRequest.Mode.Bootstrap,
        source.namespace(),
        source.releaseName(),
        source.policy(),
        null,
        source.canaryRevision(),
        source.thresholds(),
        source.evaluationTime());
  }

  public static MetricPolicySpec policy(MetricPolicySpec.Direction direction) {
    return new MetricPolicySpec(
        direction,
        "ratio",
        "sum(rate(requests{revision=\"{{revision}}\"}[{{window}}]))",
        "sum(increase(requests{revision=\"{{revision}}\"}[{{window}}]))",
        new MetricPolicySpec.AllowedThresholds(
            new MetricPolicySpec.NumericRange(0.0, 1.0),
            new MetricPolicySpec.NumericRange(0.0, 1.0)),
        100L,
        "2m",
        "20s",
        "5s");
  }

  public static GateEvaluationService service(PrometheusQueryClient prometheus) {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    PromQueryRenderer renderer = new PromQueryRenderer();
    return new GateEvaluationService(
        new GateRequestValidator(renderer),
        renderer,
        prometheus,
        new EvidenceDigester(objectMapper),
        new GateProperties(URI.create("http://127.0.0.1:9090"), Duration.ofSeconds(10)),
        new SimpleMeterRegistry());
  }

  public static PrometheusObservation observation(String query, double value) {
    return new PrometheusObservation(query, EVALUATION_TIME, value, Map.of());
  }
}
