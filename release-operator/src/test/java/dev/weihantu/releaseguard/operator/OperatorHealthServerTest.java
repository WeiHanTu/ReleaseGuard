package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperatorHealthServerTest {
  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @Test
  void separatesLivenessFromReadiness() throws Exception {
    try (OperatorHealthServer server =
        OperatorHealthServer.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
      assertResponse(server, "/healthz", 200, "ok\n");
      assertResponse(server, "/readyz", 503, "not ready\n");

      server.markReady();

      assertResponse(server, "/readyz", 200, "ok\n");
    }
  }

  @Test
  void rejectsUnsupportedMethods() throws Exception {
    try (OperatorHealthServer server =
        OperatorHealthServer.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
      HttpRequest request =
          HttpRequest.newBuilder(localUri(server, "/healthz"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();

      assertEquals(405, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }
  }

  @Test
  void validatesConfiguredPort() {
    assertEquals(
        OperatorHealthServer.DEFAULT_PORT, OperatorHealthServer.portFromEnvironment(Map.of()));
    assertEquals(
        9090, OperatorHealthServer.portFromEnvironment(Map.of("RELEASEGUARD_HEALTH_PORT", "9090")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OperatorHealthServer.portFromEnvironment(
                Map.of("RELEASEGUARD_HEALTH_PORT", "not-a-port")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OperatorHealthServer.portFromEnvironment(Map.of("RELEASEGUARD_HEALTH_PORT", "65536")));
  }

  private void assertResponse(OperatorHealthServer server, String path, int status, String body)
      throws Exception {
    HttpRequest request = HttpRequest.newBuilder(localUri(server, path)).GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode());
    assertEquals(body, response.body());
  }

  private URI localUri(OperatorHealthServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }
}
