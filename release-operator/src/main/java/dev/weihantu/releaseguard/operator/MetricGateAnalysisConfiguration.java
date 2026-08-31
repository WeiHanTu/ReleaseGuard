package dev.weihantu.releaseguard.operator;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record MetricGateAnalysisConfiguration(
    URI evaluationUrl, String authSecretName, String authSecretKey) {
  public static final String EVALUATION_URL_ENV = "RELEASEGUARD_ANALYSIS_GATE_URL";
  public static final String AUTH_SECRET_NAME_ENV = "RELEASEGUARD_ANALYSIS_AUTH_SECRET_NAME";
  public static final String AUTH_SECRET_KEY_ENV = "RELEASEGUARD_ANALYSIS_AUTH_SECRET_KEY";
  private static final Pattern SECRET_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
  private static final Pattern SECRET_KEY = Pattern.compile("[A-Za-z0-9._-]+");
  public static final MetricGateAnalysisConfiguration DEFAULT =
      new MetricGateAnalysisConfiguration(
          URI.create(
              "http://releaseguard-metric-gate.releaseguard-system.svc.cluster.local:8080/internal/v1/evaluations"),
          "releaseguard-metric-gate-auth",
          "token");

  public MetricGateAnalysisConfiguration {
    Objects.requireNonNull(evaluationUrl, "evaluationUrl");
    requireHttpUrl(evaluationUrl);
    if (authSecretName == null
        || authSecretName.length() > 253
        || !SECRET_NAME.matcher(authSecretName).matches()) {
      throw new IllegalArgumentException("authSecretName must be a valid Kubernetes Secret name");
    }
    if (authSecretKey == null || !SECRET_KEY.matcher(authSecretKey).matches()) {
      throw new IllegalArgumentException("authSecretKey must be a valid Secret data key");
    }
  }

  public static MetricGateAnalysisConfiguration fromEnvironment(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment");
    return new MetricGateAnalysisConfiguration(
        URI.create(
            environment.getOrDefault(EVALUATION_URL_ENV, DEFAULT.evaluationUrl().toString())),
        environment.getOrDefault(AUTH_SECRET_NAME_ENV, DEFAULT.authSecretName()),
        environment.getOrDefault(AUTH_SECRET_KEY_ENV, DEFAULT.authSecretKey()));
  }

  private static void requireHttpUrl(URI url) {
    if (!url.isAbsolute() || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))) {
      throw new IllegalArgumentException("evaluationUrl must be an absolute HTTP(S) URL");
    }
    if (url.getUserInfo() != null || url.getHost() == null || url.getHost().isBlank()) {
      throw new IllegalArgumentException("evaluationUrl must have a host and no user info");
    }
  }
}
