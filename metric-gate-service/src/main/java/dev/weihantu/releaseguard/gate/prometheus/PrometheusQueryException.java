package dev.weihantu.releaseguard.gate.prometheus;

public final class PrometheusQueryException extends Exception {
  private final PrometheusFailure failure;

  public PrometheusQueryException(PrometheusFailure failure) {
    super(failure.reason());
    this.failure = failure;
  }

  public PrometheusQueryException(PrometheusFailure failure, Throwable cause) {
    super(failure.reason(), cause);
    this.failure = failure;
  }

  public PrometheusFailure failure() {
    return failure;
  }
}
