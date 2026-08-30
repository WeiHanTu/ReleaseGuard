package dev.weihantu.releaseguard.gate.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("releaseguard.gate")
public record GateProperties(URI prometheusBaseUrl, Duration requestTimeout) {
  public GateProperties {
    Objects.requireNonNull(prometheusBaseUrl, "prometheusBaseUrl");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
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
  }
}
