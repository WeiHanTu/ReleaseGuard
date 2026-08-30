package dev.weihantu.releaseguard.gate.prometheus;

public enum PrometheusFailure {
  EmptyVector("EmptyVector"),
  UnexpectedSeries("UnexpectedSeries"),
  NonFiniteValue("NonFiniteValue"),
  PartialProviderResponse("PartialProviderResponse"),
  ProviderTimeout("ProviderTimeout"),
  ProviderError("ProviderError"),
  MalformedProviderResponse("MalformedProviderResponse");

  private final String reason;

  PrometheusFailure(String reason) {
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
