package dev.weihantu.releaseguard.api.validation;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventSpec;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ReleaseEventValidator {
  private static final Pattern EVENT_ID = Pattern.compile("^sha256:[0-9a-f]{64}$");
  private static final Pattern RELEASE_ID = Pattern.compile("^.+:[1-9][0-9]*$");
  private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$");

  public ValidationResult validate(ReleaseEventSpec spec) {
    List<ValidationError> errors = new ArrayList<>();
    if (spec == null) {
      add(errors, "RG-OUT-001", "spec", "spec is required");
      return ValidationResult.from(errors);
    }
    if (spec.eventId() == null || !EVENT_ID.matcher(spec.eventId()).matches()) {
      add(errors, "RG-OUT-001", "spec.eventId", "event ID must be a lowercase sha256 digest");
    }
    if (spec.releaseId() == null || !RELEASE_ID.matcher(spec.releaseId()).matches()) {
      add(
          errors,
          "RG-ID-003",
          "spec.releaseId",
          "release ID must contain UID and positive generation");
    }
    if (spec.sequence() == null || spec.sequence() < 1) {
      add(errors, "RG-OUT-001", "spec.sequence", "sequence must be positive");
    }
    if (spec.type() == null || !EVENT_TYPE.matcher(spec.type()).matches()) {
      add(errors, "RG-OUT-001", "spec.type", "event type must be a stable machine-readable name");
    }
    if (!isRfc3339(spec.occurredAt())) {
      add(errors, "RG-OUT-001", "spec.occurredAt", "event time must be RFC 3339");
    }
    if (spec.payload() == null) {
      add(errors, "RG-OUT-001", "spec.payload", "payload is required");
    }
    return ValidationResult.from(errors);
  }

  private static boolean isRfc3339(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      OffsetDateTime.parse(value);
      return true;
    } catch (DateTimeParseException exception) {
      return false;
    }
  }

  private static void add(
      List<ValidationError> errors, String requirement, String path, String message) {
    errors.add(new ValidationError(requirement, path, message));
  }
}
