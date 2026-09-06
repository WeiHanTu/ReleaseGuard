package dev.weihantu.releaseguard.history.api;

import java.util.List;

public record ApiError(
    String code, String message, String traceId, List<FieldViolation> fieldViolations) {

  public ApiError {
    fieldViolations = fieldViolations == null ? List.of() : List.copyOf(fieldViolations);
  }

  public ApiError(String code, String message) {
    this(code, message, null, List.of());
  }

  public ApiError(String code, String message, List<FieldViolation> fieldViolations) {
    this(code, message, null, fieldViolations);
  }

  public record FieldViolation(String field, String message) {}
}
