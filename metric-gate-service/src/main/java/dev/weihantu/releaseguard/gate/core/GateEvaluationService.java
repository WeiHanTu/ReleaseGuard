package dev.weihantu.releaseguard.gate.core;

import dev.weihantu.releaseguard.api.v1alpha1.KubernetesDuration;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.gate.api.GateEvaluationRequest;
import dev.weihantu.releaseguard.gate.api.GateEvaluationResponse;
import dev.weihantu.releaseguard.gate.api.GateEvaluationResponse.Decision;
import dev.weihantu.releaseguard.gate.api.GateEvaluationResponse.Observation;
import dev.weihantu.releaseguard.gate.config.GateProperties;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusObservation;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusQueryClient;
import dev.weihantu.releaseguard.gate.prometheus.PrometheusQueryException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GateEvaluationService {
  private static final String CANARY = "Canary";
  private static final String STABLE = "Stable";
  private static final String VALUE = "Value";
  private static final String SAMPLE_COUNT = "SampleCount";

  private final GateRequestValidator validator;
  private final PromQueryRenderer queryRenderer;
  private final PrometheusQueryClient prometheus;
  private final EvidenceDigester evidenceDigester;
  private final Duration requestTimeout;
  private final MeterRegistry meterRegistry;

  public GateEvaluationService(
      GateRequestValidator validator,
      PromQueryRenderer queryRenderer,
      PrometheusQueryClient prometheus,
      EvidenceDigester evidenceDigester,
      GateProperties properties,
      MeterRegistry meterRegistry) {
    this.validator = validator;
    this.queryRenderer = queryRenderer;
    this.prometheus = prometheus;
    this.evidenceDigester = evidenceDigester;
    this.requestTimeout = properties.requestTimeout();
    this.meterRegistry = meterRegistry;
  }

  public GateEvaluationResponse evaluate(GateEvaluationRequest request) {
    validator.validate(request);
    MetricPolicySpec policy = request.policy().spec();
    Duration window = KubernetesDuration.parse(policy.window()).orElseThrow();
    Duration providerTimeout = KubernetesDuration.parse(policy.timeout()).orElseThrow();
    Instant windowEnd = request.evaluationTime();
    Instant windowStart = windowEnd.minus(window);
    long deadline = System.nanoTime() + requestTimeout.toNanos();
    EvaluationState state = new EvaluationState();

    QueryResult canaryValue =
        query(
            request,
            CANARY,
            VALUE,
            request.canaryRevision(),
            policy.valueQueryTemplate(),
            providerTimeout,
            deadline,
            windowStart,
            windowEnd,
            state);
    if (canaryValue.failure() != null) {
      return response(
          request, Decision.Inconclusive, canaryValue.failure(), state, windowStart, windowEnd);
    }
    state.canaryValue = canaryValue.value();

    QueryResult canarySamples =
        query(
            request,
            CANARY,
            SAMPLE_COUNT,
            request.canaryRevision(),
            policy.sampleCountQueryTemplate(),
            providerTimeout,
            deadline,
            windowStart,
            windowEnd,
            state);
    if (canarySamples.failure() != null) {
      return response(
          request, Decision.Inconclusive, canarySamples.failure(), state, windowStart, windowEnd);
    }
    state.canarySamples = canarySamples.value();

    if (request.mode() == GateEvaluationRequest.Mode.Canary) {
      QueryResult stableValue =
          query(
              request,
              STABLE,
              VALUE,
              request.stableRevision(),
              policy.valueQueryTemplate(),
              providerTimeout,
              deadline,
              windowStart,
              windowEnd,
              state);
      if (stableValue.failure() != null) {
        return response(
            request, Decision.Inconclusive, stableValue.failure(), state, windowStart, windowEnd);
      }
      state.stableValue = stableValue.value();

      QueryResult stableSamples =
          query(
              request,
              STABLE,
              SAMPLE_COUNT,
              request.stableRevision(),
              policy.sampleCountQueryTemplate(),
              providerTimeout,
              deadline,
              windowStart,
              windowEnd,
              state);
      if (stableSamples.failure() != null) {
        return response(
            request, Decision.Inconclusive, stableSamples.failure(), state, windowStart, windowEnd);
      }
      state.stableSamples = stableSamples.value();
    }

    if (state.canarySamples < policy.minimumSamples()
        || (request.mode() == GateEvaluationRequest.Mode.Canary
            && state.stableSamples < policy.minimumSamples())) {
      return response(
          request, Decision.Inconclusive, "InsufficientSamples", state, windowStart, windowEnd);
    }

    Boolean absolutePass = absolutePass(request, policy.direction(), state.canaryValue);
    Boolean baselinePass = baselinePass(request, policy.direction(), state);
    if (Boolean.FALSE.equals(absolutePass) && Boolean.FALSE.equals(baselinePass)) {
      return response(
          request,
          Decision.Fail,
          "AbsoluteAndBaselineThresholdsBreached",
          state,
          windowStart,
          windowEnd);
    }
    if (Boolean.FALSE.equals(absolutePass)) {
      return response(
          request, Decision.Fail, "AbsoluteThresholdBreached", state, windowStart, windowEnd);
    }
    if (Boolean.FALSE.equals(baselinePass)) {
      return response(
          request, Decision.Fail, "BaselineDeltaBreached", state, windowStart, windowEnd);
    }
    String reason =
        request.mode() == GateEvaluationRequest.Mode.Bootstrap
            ? "WithinAbsoluteLimit"
            : "WithinAbsoluteAndBaselineLimits";
    return response(request, Decision.Pass, reason, state, windowStart, windowEnd);
  }

  private QueryResult query(
      GateEvaluationRequest request,
      String revisionRole,
      String measurement,
      String revision,
      String template,
      Duration providerTimeout,
      long deadline,
      Instant windowStart,
      Instant windowEnd,
      EvaluationState state) {
    String query = queryRenderer.render(template, substitutions(request, revision));
    Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    if (remaining.isZero()) {
      state.observations.add(
          new Observation(
              revisionRole, measurement, query, null, null, Map.of(), "RequestDeadlineExceeded"));
      return QueryResult.failed("RequestDeadlineExceeded");
    }
    Duration timeout = providerTimeout.compareTo(remaining) < 0 ? providerTimeout : remaining;
    try {
      PrometheusObservation observation =
          prometheus.query(query, request.evaluationTime(), timeout);
      String failure = observationFailure(observation, measurement, windowStart, windowEnd);
      state.observations.add(
          new Observation(
              revisionRole,
              measurement,
              query,
              observation.observedAt(),
              observation.value(),
              observation.labels(),
              failure == null ? "Complete" : failure));
      return failure == null
          ? QueryResult.succeeded(observation.value())
          : QueryResult.failed(failure);
    } catch (PrometheusQueryException exception) {
      String failure = exception.failure().reason();
      state.observations.add(
          new Observation(revisionRole, measurement, query, null, null, Map.of(), failure));
      return QueryResult.failed(failure);
    }
  }

  private static Map<String, String> substitutions(GateEvaluationRequest request, String revision) {
    Map<String, String> substitutions = new LinkedHashMap<>();
    substitutions.put("revision", revision);
    substitutions.put("namespace", request.namespace());
    substitutions.put("releaseName", request.releaseName());
    substitutions.put("window", request.policy().spec().window());
    return Map.copyOf(substitutions);
  }

  private static String observationFailure(
      PrometheusObservation observation,
      String measurement,
      Instant windowStart,
      Instant windowEnd) {
    if (!Double.isFinite(observation.value())) {
      return "NonFiniteValue";
    }
    if (observation.observedAt().isBefore(windowStart)
        || observation.observedAt().isAfter(windowEnd)) {
      return "StaleObservation";
    }
    if (SAMPLE_COUNT.equals(measurement) && observation.value() < 0) {
      return "InvalidSampleCount";
    }
    return null;
  }

  private static Boolean absolutePass(
      GateEvaluationRequest request, MetricPolicySpec.Direction direction, double canaryValue) {
    GateEvaluationRequest.Thresholds thresholds = request.thresholds();
    if (direction == MetricPolicySpec.Direction.LowerIsBetter) {
      return thresholds.maxCanaryValue() == null
          ? null
          : canaryValue <= thresholds.maxCanaryValue();
    }
    return thresholds.minCanaryValue() == null ? null : canaryValue >= thresholds.minCanaryValue();
  }

  private static Boolean baselinePass(
      GateEvaluationRequest request, MetricPolicySpec.Direction direction, EvaluationState state) {
    if (request.mode() == GateEvaluationRequest.Mode.Bootstrap
        || request.thresholds().maxDeltaFromStable() == null) {
      return null;
    }
    double delta = request.thresholds().maxDeltaFromStable();
    return direction == MetricPolicySpec.Direction.LowerIsBetter
        ? state.canaryValue - state.stableValue <= delta
        : state.stableValue - state.canaryValue <= delta;
  }

  private GateEvaluationResponse response(
      GateEvaluationRequest request,
      Decision decision,
      String reason,
      EvaluationState state,
      Instant windowStart,
      Instant windowEnd) {
    List<Observation> observations = List.copyOf(state.observations);
    DecisionEvidence evidence =
        new DecisionEvidence(
            request,
            decision,
            reason,
            state.stableValue,
            state.canaryValue,
            state.stableSamples,
            state.canarySamples,
            windowStart,
            windowEnd,
            observations);
    String digest = evidenceDigester.digest(evidence);
    Counter.builder("releaseguard.gate.evaluations")
        .description("Metric-gate decisions")
        .tag("decision", decision.name())
        .tag("reason", reason)
        .register(meterRegistry)
        .increment();
    return new GateEvaluationResponse(
        decision,
        reason,
        state.stableValue,
        state.canaryValue,
        state.stableSamples,
        state.canarySamples,
        windowStart,
        windowEnd,
        request.policy().resourceVersion(),
        digest,
        observations);
  }

  private static final class EvaluationState {
    private Double stableValue;
    private Double canaryValue;
    private Double stableSamples;
    private Double canarySamples;
    private final List<Observation> observations = new ArrayList<>();
  }

  private record QueryResult(Double value, String failure) {
    static QueryResult succeeded(double value) {
      return new QueryResult(value, null);
    }

    static QueryResult failed(String failure) {
      return new QueryResult(null, failure);
    }
  }

  private record DecisionEvidence(
      GateEvaluationRequest request,
      Decision decision,
      String reason,
      Double stableValue,
      Double canaryValue,
      Double stableSamples,
      Double canarySamples,
      Instant windowStart,
      Instant windowEnd,
      List<Observation> observations) {}
}
