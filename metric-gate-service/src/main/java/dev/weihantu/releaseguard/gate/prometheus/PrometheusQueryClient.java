package dev.weihantu.releaseguard.gate.prometheus;

import java.time.Duration;
import java.time.Instant;

public interface PrometheusQueryClient {
  PrometheusObservation query(String query, Instant evaluationTime, Duration timeout)
      throws PrometheusQueryException;
}
