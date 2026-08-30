package dev.weihantu.releaseguard.gate.prometheus;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.gate.GateTestFixtures;
import dev.weihantu.releaseguard.gate.api.GateApiExceptionHandler;
import dev.weihantu.releaseguard.gate.api.GateEvaluationController;
import dev.weihantu.releaseguard.gate.config.GateProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GateEvaluationControllerIT {
  private FakePrometheusServer prometheus;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() throws IOException {
    prometheus = new FakePrometheusServer();
    PrometheusHttpClient client =
        new PrometheusHttpClient(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            new GateProperties(prometheus.baseUrl(), Duration.ofSeconds(2)),
            new SimpleMeterRegistry());
    GateEvaluationController controller =
        new GateEvaluationController(GateTestFixtures.service(client));
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GateApiExceptionHandler())
            .build();
  }

  @AfterEach
  void tearDown() {
    prometheus.close();
  }

  @Test
  void evaluatesTheHttpContractAgainstFakePrometheus() throws Exception {
    prometheus.enqueue(PrometheusHttpClientIT.success(0.004));
    prometheus.enqueue(PrometheusHttpClientIT.success(180));
    prometheus.enqueue(PrometheusHttpClientIT.success(0.002));
    prometheus.enqueue(PrometheusHttpClientIT.success(1200));

    mockMvc
        .perform(
            post("/internal/v1/evaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("Pass"))
        .andExpect(jsonPath("$.reason").value("WithinAbsoluteAndBaselineLimits"))
        .andExpect(jsonPath("$.stableValue").value(0.002))
        .andExpect(jsonPath("$.canaryValue").value(0.004))
        .andExpect(jsonPath("$.stableSamples").value(1200.0))
        .andExpect(jsonPath("$.canarySamples").value(180.0))
        .andExpect(jsonPath("$.observations.length()").value(4))
        .andExpect(jsonPath("$.evidenceDigest", matchesPattern("sha256:[0-9a-f]{64}")));

    assertEquals(4, prometheus.requests().size());
    String canaryQuery = prometheus.requests().get(0).parameters().get("query");
    String stableQuery = prometheus.requests().get(2).parameters().get("query");
    assertNotEquals(canaryQuery, stableQuery);
  }

  @Test
  void rejectsMalformedOrUnsafeRequestsBeforeCallingPrometheus() throws Exception {
    String invalid = validRequest().replace("\"maxCanaryValue\":0.01", "\"maxCanaryValue\":2.0");

    mockMvc
        .perform(
            post("/internal/v1/evaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("InvalidEvaluationRequest"))
        .andExpect(jsonPath("$.violations[0].path").value("thresholds.maxCanaryValue"));

    assertEquals(0, prometheus.requests().size());
  }

  private static String validRequest() {
    return """
        {
          "releaseId":"release-uid:4",
          "step":1,
          "mode":"Canary",
          "namespace":"demo",
          "releaseName":"checkout",
          "policy":{
            "name":"http-errors",
            "resourceVersion":"817263",
            "spec":{
              "direction":"LowerIsBetter",
              "unit":"ratio",
              "valueQueryTemplate":"sum(rate(requests{revision=\\\"{{revision}}\\\"}[{{window}}]))",
              "sampleCountQueryTemplate":"sum(increase(requests{revision=\\\"{{revision}}\\\"}[{{window}}]))",
              "allowedThresholds":{
                "canary":{"minimum":0.0,"maximum":1.0},
                "stableDelta":{"minimum":0.0,"maximum":1.0}
              },
              "minimumSamples":100,
              "window":"2m",
              "interval":"20s",
              "timeout":"1s"
            }
          },
          "stableRevision":"checkout-stable",
          "canaryRevision":"checkout-canary",
          "thresholds":{"maxCanaryValue":0.01,"maxDeltaFromStable":0.005},
          "evaluationTime":"2026-08-26T21:00:00Z"
        }
        """;
  }
}
