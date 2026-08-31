package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricGateAnalysisConfigurationTest {
  @Test
  void loadsExplicitEnvironmentWithoutAcceptingCredentialsInTheUrl() {
    MetricGateAnalysisConfiguration configuration =
        MetricGateAnalysisConfiguration.fromEnvironment(
            Map.of(
                MetricGateAnalysisConfiguration.EVALUATION_URL_ENV,
                "https://gate.releaseguard.svc/internal/v1/evaluations",
                MetricGateAnalysisConfiguration.AUTH_SECRET_NAME_ENV,
                "releaseguard-analysis-auth",
                MetricGateAnalysisConfiguration.AUTH_SECRET_KEY_ENV,
                "bearer-token"));

    assertEquals(
        URI.create("https://gate.releaseguard.svc/internal/v1/evaluations"),
        configuration.evaluationUrl());
    assertEquals("releaseguard-analysis-auth", configuration.authSecretName());
    assertEquals("bearer-token", configuration.authSecretKey());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetricGateAnalysisConfiguration(
                URI.create("https://user:password@gate.example/evaluate"), "auth", "token"));
  }

  @Test
  void rejectsInvalidSecretReferencesAndNonHttpUrls() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetricGateAnalysisConfiguration(
                URI.create("file:///tmp/gate"), "releaseguard-auth", "token"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetricGateAnalysisConfiguration(
                URI.create("http://gate.example/evaluate"), "INVALID_NAME", "token"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetricGateAnalysisConfiguration(
                URI.create("http://gate.example/evaluate"), "releaseguard-auth", "bad/key"));
  }
}
