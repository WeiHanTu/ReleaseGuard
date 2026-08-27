package dev.weihantu.releaseguard.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.KubernetesDuration;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class KubernetesDurationTest {
  @Test
  void parsesSupportedUnits() {
    assertEquals(Duration.ofMillis(250), KubernetesDuration.parse("250ms").orElseThrow());
    assertEquals(Duration.ofSeconds(10), KubernetesDuration.parse("10s").orElseThrow());
    assertEquals(Duration.ofMinutes(2), KubernetesDuration.parse("2m").orElseThrow());
    assertEquals(Duration.ofHours(24), KubernetesDuration.parse("24h").orElseThrow());
  }

  @Test
  void rejectsZeroFractionsUnknownUnitsAndOverflow() {
    assertTrue(KubernetesDuration.parse("0s").isEmpty());
    assertTrue(KubernetesDuration.parse("1.5m").isEmpty());
    assertTrue(KubernetesDuration.parse("2d").isEmpty());
    assertTrue(KubernetesDuration.parse("999999999999999999999h").isEmpty());
  }
}
