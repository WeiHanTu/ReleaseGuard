package dev.weihantu.releaseguard.gate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "releaseguard.gate.prometheus-base-url=http://127.0.0.1:9090",
      "releaseguard.gate.request-timeout=10s"
    })
class MetricGateApplicationTest {
  @Test
  void applicationContextLoads() {}
}
