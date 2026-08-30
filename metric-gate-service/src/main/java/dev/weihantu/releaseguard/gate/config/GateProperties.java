package dev.weihantu.releaseguard.gate.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("releaseguard.gate")
public record GateProperties(URI prometheusBaseUrl, Duration requestTimeout, String authToken) {
  public GateProperties {
    Objects.requireNonNull(prometheusBaseUrl, "prometheusBaseUrl");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(authToken, "authToken");
    if (!"http".equals(prometheusBaseUrl.getScheme())
        && !"https".equals(prometheusBaseUrl.getScheme())) {
      throw new IllegalArgumentException("prometheusBaseUrl must use http or https");
    }
    if (prometheusBaseUrl.getUserInfo() != null) {
      throw new IllegalArgumentException("prometheusBaseUrl must not contain credentials");
    }
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    if (requestTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("requestTimeout must not exceed one minute");
    }
    if (!authToken.equals(authToken.strip())) {
      throw new IllegalArgumentException("authToken must not have surrounding whitespace");
    }
    if (authToken.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException("authToken must contain at least 32 UTF-8 bytes");
    }
  }

  @Override
  public String toString() {
    return "GateProperties[prometheusBaseUrl="
        + prometheusBaseUrl
        + ", requestTimeout="
        + requestTimeout
        + ", authToken=<redacted>]";
  }
}
