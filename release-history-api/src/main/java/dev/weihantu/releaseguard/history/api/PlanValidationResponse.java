package dev.weihantu.releaseguard.history.api;

import java.util.List;

public record PlanValidationResponse(
    boolean valid, int acceptedPoliciesCount, List<ValidationErrorView> errors) {

  public PlanValidationResponse {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public record ValidationErrorView(String requirement, String path, String message) {}
}
