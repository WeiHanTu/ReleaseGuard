package dev.weihantu.releaseguard.gate.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.weihantu.releaseguard.gate.GateTestFixtures;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatePropertiesTest {
  private static final URI PROMETHEUS = URI.create("http://127.0.0.1:9090");

  @Test
  void rejectsTokensShorterThan32Bytes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GateProperties(PROMETHEUS, Duration.ofSeconds(10), "too-short"));
  }

  @Test
  void rejectsTokensWithSurroundingWhitespace() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GateProperties(
                PROMETHEUS, Duration.ofSeconds(10), " " + GateTestFixtures.AUTH_TOKEN));
  }

  @Test
  void redactsTheTokenFromDiagnosticRendering() {
    GateProperties properties =
        new GateProperties(PROMETHEUS, Duration.ofSeconds(10), GateTestFixtures.AUTH_TOKEN);

    assertFalse(properties.toString().contains(GateTestFixtures.AUTH_TOKEN));
  }
}
