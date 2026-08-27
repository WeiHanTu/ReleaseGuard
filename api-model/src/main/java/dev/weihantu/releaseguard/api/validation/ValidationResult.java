package dev.weihantu.releaseguard.api.validation;

import java.util.Comparator;
import java.util.List;

public record ValidationResult(List<ValidationError> errors) {
  public ValidationResult {
    errors =
        errors.stream()
            .sorted(
                Comparator.comparing(ValidationError::path)
                    .thenComparing(ValidationError::requirement)
                    .thenComparing(ValidationError::message))
            .toList();
  }

  public static ValidationResult from(List<ValidationError> errors) {
    return new ValidationResult(List.copyOf(errors));
  }

  public boolean isValid() {
    return errors.isEmpty();
  }

  public boolean hasRequirement(String requirement) {
    return errors.stream().anyMatch(error -> error.requirement().equals(requirement));
  }
}
