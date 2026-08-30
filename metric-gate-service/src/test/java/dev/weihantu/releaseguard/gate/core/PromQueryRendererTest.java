package dev.weihantu.releaseguard.gate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PromQueryRendererTest {
  private final PromQueryRenderer renderer = new PromQueryRenderer();

  @Test
  void escapesUntrustedLabelValuesWithoutChangingTheQueryStructure() {
    String query =
        renderer.render(
            "rate(requests{revision=\"{{revision}}\"}[{{window}}])",
            Map.of("revision", "candidate\\\"}\n or up", "window", "2m"));

    assertEquals("rate(requests{revision=\"candidate\\\\\\\"}\\n or up\"}[2m])", query);
  }

  @Test
  void rejectsLabelSubstitutionsOutsideStringLiterals() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            renderer.render(
                "rate(requests{revision={{revision}}}[{{window}}])",
                Map.of("revision", "candidate", "window", "2m")));
  }

  @Test
  void rejectsQuotedDurationSubstitutions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            renderer.render(
                "rate(requests{revision=\"{{revision}}\"}[\"{{window}}\"])",
                Map.of("revision", "candidate", "window", "2m")));
  }
}
