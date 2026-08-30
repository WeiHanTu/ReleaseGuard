package dev.weihantu.releaseguard.operator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class OperatorHealthServer implements AutoCloseable {
  static final int DEFAULT_PORT = 8081;

  private final HttpServer server;
  private final ExecutorService executor;
  private final AtomicBoolean ready = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();

  private OperatorHealthServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  static OperatorHealthServer start(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "releaseguard-health");
              thread.setDaemon(true);
              return thread;
            });
    OperatorHealthServer healthServer = new OperatorHealthServer(server, executor);
    server.createContext("/healthz", exchange -> healthServer.respond(exchange, true));
    server.createContext(
        "/readyz", exchange -> healthServer.respond(exchange, healthServer.ready.get()));
    server.setExecutor(executor);
    server.start();
    return healthServer;
  }

  static int portFromEnvironment(Map<String, String> environment) {
    String configured = environment.get("RELEASEGUARD_HEALTH_PORT");
    if (configured == null || configured.isBlank()) {
      return DEFAULT_PORT;
    }

    final int port;
    try {
      port = Integer.parseInt(configured);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("RELEASEGUARD_HEALTH_PORT must be an integer", exception);
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("RELEASEGUARD_HEALTH_PORT must be between 1 and 65535");
    }
    return port;
  }

  void markReady() {
    ready.set(true);
  }

  int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    ready.set(false);
    server.stop(0);
    executor.shutdownNow();
  }

  private void respond(HttpExchange exchange, boolean healthy) throws IOException {
    try (exchange) {
      if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      byte[] body = (healthy ? "ok\n" : "not ready\n").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(healthy ? 200 : 503, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
