package dev.weihantu.releaseguard.gate.prometheus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.gate.config.GateProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class PrometheusHttpClient implements PrometheusQueryClient {
  private static final String QUERY_PATH = "/api/v1/query";

  private final HttpClient client;
  private final ObjectMapper objectMapper;
  private final URI baseUrl;
  private final MeterRegistry meterRegistry;

  public PrometheusHttpClient(
      HttpClient client,
      ObjectMapper objectMapper,
      GateProperties properties,
      MeterRegistry meterRegistry) {
    this.client = client;
    this.objectMapper = objectMapper;
    this.baseUrl = properties.prometheusBaseUrl();
    this.meterRegistry = meterRegistry;
  }

  @Override
  public PrometheusObservation query(String query, Instant evaluationTime, Duration timeout)
      throws PrometheusQueryException {
    Timer.Sample timer = Timer.start(meterRegistry);
    String outcome = "success";
    try {
      HttpRequest request =
          HttpRequest.newBuilder(endpoint(query, evaluationTime, timeout))
              .timeout(timeout)
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new PrometheusQueryException(PrometheusFailure.ProviderError);
      }
      return decode(query, response.body());
    } catch (HttpTimeoutException exception) {
      outcome = "timeout";
      throw new PrometheusQueryException(PrometheusFailure.ProviderTimeout, exception);
    } catch (InterruptedException exception) {
      outcome = "interrupted";
      Thread.currentThread().interrupt();
      throw new PrometheusQueryException(PrometheusFailure.ProviderError, exception);
    } catch (IOException exception) {
      outcome = "io_error";
      throw new PrometheusQueryException(PrometheusFailure.ProviderError, exception);
    } catch (PrometheusQueryException exception) {
      outcome = exception.failure().reason();
      throw exception;
    } finally {
      timer.stop(
          Timer.builder("releaseguard.prometheus.query")
              .description("Prometheus instant-query latency")
              .tag("outcome", outcome)
              .register(meterRegistry));
    }
  }

  private URI endpoint(String query, Instant evaluationTime, Duration timeout) {
    String base = baseUrl.toString();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    String parameters =
        "query="
            + encode(query)
            + "&time="
            + encode(evaluationTime.toString())
            + "&timeout="
            + timeout.toMillis()
            + "ms";
    return URI.create(base + QUERY_PATH + "?" + parameters);
  }

  private PrometheusObservation decode(String query, String body) throws PrometheusQueryException {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (JsonProcessingException exception) {
      throw new PrometheusQueryException(PrometheusFailure.MalformedProviderResponse, exception);
    }
    if (!"success".equals(root.path("status").asText())) {
      throw new PrometheusQueryException(PrometheusFailure.ProviderError);
    }
    if (root.has("warnings") && !root.path("warnings").isEmpty()) {
      throw new PrometheusQueryException(PrometheusFailure.PartialProviderResponse);
    }
    JsonNode data = root.path("data");
    if (!"vector".equals(data.path("resultType").asText()) || !data.path("result").isArray()) {
      throw new PrometheusQueryException(PrometheusFailure.MalformedProviderResponse);
    }
    JsonNode result = data.path("result");
    if (result.isEmpty()) {
      throw new PrometheusQueryException(PrometheusFailure.EmptyVector);
    }
    if (result.size() != 1) {
      throw new PrometheusQueryException(PrometheusFailure.UnexpectedSeries);
    }
    JsonNode series = result.get(0);
    JsonNode value = series.path("value");
    if (!value.isArray() || value.size() != 2) {
      throw new PrometheusQueryException(PrometheusFailure.MalformedProviderResponse);
    }
    double timestamp = number(value.get(0));
    double sample = number(value.get(1));
    if (!Double.isFinite(timestamp) || !Double.isFinite(sample)) {
      throw new PrometheusQueryException(PrometheusFailure.NonFiniteValue);
    }
    long seconds = (long) Math.floor(timestamp);
    long nanos = Math.round((timestamp - seconds) * 1_000_000_000d);
    if (nanos == 1_000_000_000L) {
      seconds++;
      nanos = 0;
    }
    Map<String, String> labels = new TreeMap<>();
    JsonNode metric = series.path("metric");
    if (!metric.isObject()) {
      throw new PrometheusQueryException(PrometheusFailure.MalformedProviderResponse);
    }
    metric.properties().forEach(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
    Instant observedAt;
    try {
      observedAt = Instant.ofEpochSecond(seconds, nanos);
    } catch (DateTimeException exception) {
      throw new PrometheusQueryException(PrometheusFailure.MalformedProviderResponse, exception);
    }
    return new PrometheusObservation(query, observedAt, sample, Map.copyOf(labels));
  }

  private static double number(JsonNode value) throws PrometheusQueryException {
    if (value == null || (!value.isNumber() && !value.isTextual())) {
      throw new PrometheusQueryException(PrometheusFailure.MalformedProviderResponse);
    }
    try {
      return value.isNumber() ? value.doubleValue() : Double.parseDouble(value.asText());
    } catch (NumberFormatException exception) {
      throw new PrometheusQueryException(PrometheusFailure.MalformedProviderResponse, exception);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
