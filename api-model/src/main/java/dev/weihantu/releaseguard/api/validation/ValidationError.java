package dev.weihantu.releaseguard.api.validation;

import java.util.Objects;

public record ValidationError(String requirement, String path, String message) {
  public ValidationError {
    Objects.requireNonNull(requirement, "requirement");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(message, "message");
  }
}
