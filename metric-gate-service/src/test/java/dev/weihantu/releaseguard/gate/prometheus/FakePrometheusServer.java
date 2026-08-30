package dev.weihantu.releaseguard.gate.prometheus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

final class FakePrometheusServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentLinkedQueue<CannedResponse> responses = new ConcurrentLinkedQueue<>();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

  FakePrometheusServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/api/v1/query", this::handle);
    server.setExecutor(executor);
    server.start();
  }

  URI baseUrl() {
    return URI.create(
        "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
  }

  void enqueue(String body) {
    enqueue(200, body, Duration.ZERO);
  }

  void enqueue(int status, String body, Duration delay) {
    responses.add(new CannedResponse(status, body, delay));
  }

  List<RecordedRequest> requests() {
    return List.copyOf(requests);
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.add(new RecordedRequest(exchange.getRequestURI().getPath(), parameters(exchange)));
    CannedResponse response = responses.poll();
    if (response == null) {
      response = new CannedResponse(500, "{}", Duration.ZERO);
    }
    if (!response.delay().isZero()) {
      try {
        Thread.sleep(response.delay());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    try {
      exchange.sendResponseHeaders(response.status(), body.length);
      exchange.getResponseBody().write(body);
    } finally {
      exchange.close();
    }
  }

  private static Map<String, String> parameters(HttpExchange exchange) {
    String rawQuery = exchange.getRequestURI().getRawQuery();
    if (rawQuery == null || rawQuery.isBlank()) {
      return Map.of();
    }
    return List.of(rawQuery.split("&")).stream()
        .map(part -> part.split("=", 2))
        .collect(
            Collectors.toUnmodifiableMap(
                pair -> decode(pair[0]), pair -> pair.length == 2 ? decode(pair[1]) : ""));
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }

  record RecordedRequest(String path, Map<String, String> parameters) {
    RecordedRequest {
      parameters = Map.copyOf(parameters);
    }
  }

  private record CannedResponse(int status, String body, Duration delay) {}
}
