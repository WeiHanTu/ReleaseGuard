package dev.weihantu.releaseguard.api.validation;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionSpec;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class ReleaseActionValidator {
  public ValidationResult validate(ReleaseActionSpec spec) {
    List<ValidationError> errors = new ArrayList<>();
    if (spec == null) {
      add(errors, "RG-ACT-001", "spec", "spec is required");
      return ValidationResult.from(errors);
    }
    if (spec.target() == null
        || isBlank(spec.target().name())
        || isBlank(spec.target().uid())
        || spec.target().generation() == null
        || spec.target().generation() < 1) {
      add(
          errors,
          "RG-ACT-001",
          "spec.target",
          "target name, UID, and positive generation are required");
    }
    if (spec.type() == null) {
      add(errors, "RG-ACT-001", "spec.type", "action type is required");
    }
    if (isBlank(spec.reason())) {
      add(errors, "RG-ACT-003", "spec.reason", "nonempty reason is required");
    }
    if (isBlank(spec.idempotencyKey())) {
      add(errors, "RG-ACT-003", "spec.idempotencyKey", "idempotency key is required");
    }
    if (isBlank(spec.actor())) {
      add(errors, "RG-ACT-003", "spec.actor", "authenticated actor is required");
    }
    if (!isRfc3339(spec.requestedAt())) {
      add(errors, "RG-ACT-003", "spec.requestedAt", "request time must be RFC 3339");
    }
    return ValidationResult.from(errors);
  }

  private static boolean isRfc3339(String value) {
    if (isBlank(value)) {
      return false;
    }
    try {
      OffsetDateTime.parse(value);
      return true;
    } catch (DateTimeParseException exception) {
      return false;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void add(
      List<ValidationError> errors, String requirement, String path, String message) {
    errors.add(new ValidationError(requirement, path, message));
  }
}
