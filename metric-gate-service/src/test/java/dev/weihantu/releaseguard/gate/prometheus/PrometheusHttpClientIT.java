package dev.weihantu.releaseguard.gate.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.gate.GateTestFixtures;
import dev.weihantu.releaseguard.gate.config.GateProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PrometheusHttpClientIT {
  private FakePrometheusServer server;
  private PrometheusHttpClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = new FakePrometheusServer();
    client =
        new PrometheusHttpClient(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            new GateProperties(
                server.baseUrl(), Duration.ofSeconds(2), GateTestFixtures.AUTH_TOKEN),
            new SimpleMeterRegistry());
  }

  @AfterEach
  void stopServer() {
    server.close();
  }

  @Test
  void queriesTheInstantApiAndNormalizesOneVector() throws PrometheusQueryException {
    server.enqueue(success(0.004));

    PrometheusObservation observation =
        client.query(
            GateTestFixtures.CANARY_VALUE_QUERY,
            GateTestFixtures.EVALUATION_TIME,
            Duration.ofSeconds(1));

    assertEquals(0.004, observation.value());
    assertEquals(GateTestFixtures.EVALUATION_TIME, observation.observedAt());
    assertEquals(Map.of("job", "checkout"), observation.labels());
    FakePrometheusServer.RecordedRequest request = server.requests().getFirst();
    assertEquals("/api/v1/query", request.path());
    assertEquals(GateTestFixtures.CANARY_VALUE_QUERY, request.parameters().get("query"));
    assertEquals(GateTestFixtures.EVALUATION_TIME.toString(), request.parameters().get("time"));
    assertEquals("1000ms", request.parameters().get("timeout"));
  }

  @ParameterizedTest
  @MethodSource("invalidResponses")
  void classifiesUnsafeProviderResponses(String body, PrometheusFailure expected) {
    server.enqueue(body);

    PrometheusQueryException exception =
        assertThrows(
            PrometheusQueryException.class,
            () -> client.query("up", GateTestFixtures.EVALUATION_TIME, Duration.ofSeconds(1)));

    assertEquals(expected, exception.failure());
  }

  @Test
  void enforcesThePerQueryTimeout() {
    server.enqueue(200, success(1.0), Duration.ofMillis(250));

    PrometheusQueryException exception =
        assertThrows(
            PrometheusQueryException.class,
            () -> client.query("up", GateTestFixtures.EVALUATION_TIME, Duration.ofMillis(25)));

    assertEquals(PrometheusFailure.ProviderTimeout, exception.failure());
    assertTrue(server.requests().size() <= 1);
  }

  private static Stream<Arguments> invalidResponses() {
    String empty =
        """
        {"status":"success","data":{"resultType":"vector","result":[]}}
        """;
    String multiple =
        """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{},"value":[1787778000,"1"]},
          {"metric":{},"value":[1787778000,"2"]}
        ]}}
        """;
    String partial =
        """
        {"status":"success","warnings":["partial response"],
         "data":{"resultType":"vector","result":[{"metric":{},"value":[1787778000,"1"]}]}}
        """;
    String nonFinite =
        """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{},"value":[1787778000,"NaN"]}
        ]}}
        """;
    return Stream.of(
        Arguments.of(empty, PrometheusFailure.EmptyVector),
        Arguments.of(multiple, PrometheusFailure.UnexpectedSeries),
        Arguments.of(partial, PrometheusFailure.PartialProviderResponse),
        Arguments.of(nonFinite, PrometheusFailure.NonFiniteValue),
        Arguments.of("not-json", PrometheusFailure.MalformedProviderResponse));
  }

  static String success(double value) {
    return """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{"job":"checkout"},"value":[%d,"%s"]}
        ]}}
        """
        .formatted(GateTestFixtures.EVALUATION_TIME.getEpochSecond(), value);
  }
}
