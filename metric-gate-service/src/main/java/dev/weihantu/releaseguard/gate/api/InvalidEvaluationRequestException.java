package dev.weihantu.releaseguard.gate.api;

import java.util.List;

public final class InvalidEvaluationRequestException extends RuntimeException {
  private final List<Violation> violations;

  public InvalidEvaluationRequestException(List<Violation> violations) {
    super("evaluation request is invalid");
    this.violations = List.copyOf(violations);
  }

  public List<Violation> violations() {
    return violations;
  }

  public record Violation(String path, String message) {}
}
