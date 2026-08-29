package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReleaseGuardRetryTest {
  @Test
  void boundsExponentialReconciliationRetries() {
    ReleaseGuardRetry retry = new ReleaseGuardRetry();

    assertEquals(8, retry.getMaxAttempts());
    assertEquals(250, retry.getInitialInterval());
    assertEquals(2.0, retry.getIntervalMultiplier());
    assertEquals(30_000, retry.getMaxInterval());
  }
}
