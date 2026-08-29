package dev.weihantu.releaseguard.operator;

import io.javaoperatorsdk.operator.processing.retry.GenericRetry;
import java.time.Duration;

public final class ReleaseGuardRetry extends GenericRetry {
  public ReleaseGuardRetry() {
    setMaxAttempts(8)
        .setInitialInterval(Duration.ofMillis(250))
        .setIntervalMultiplier(2.0)
        .setMaxInterval(Duration.ofSeconds(30));
  }
}
