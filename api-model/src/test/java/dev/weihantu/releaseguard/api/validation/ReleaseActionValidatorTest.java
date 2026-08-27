package dev.weihantu.releaseguard.api.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionSpec;
import org.junit.jupiter.api.Test;

class ReleaseActionValidatorTest {
  private final ReleaseActionValidator validator = new ReleaseActionValidator();

  @Test
  void acceptsActionWithExactTargetAndAuditIdentity() {
    ReleaseActionSpec action =
        new ReleaseActionSpec(
            new ReleaseActionSpec.Target("checkout", "4f8f", 4L),
            ReleaseActionSpec.Type.Abort,
            "Error budget exhausted",
            "7cc18a92-4e39-4b64-a7eb-aad83ceec223",
            "user@example.com",
            "2026-08-26T21:00:00Z");

    assertTrue(validator.validate(action).isValid());
  }

  @Test
  void rejectsMissingGenerationActorReasonAndRequestTime() {
    ReleaseActionSpec action =
        new ReleaseActionSpec(
            new ReleaseActionSpec.Target("checkout", "4f8f", 0L),
            ReleaseActionSpec.Type.Abort,
            " ",
            "key",
            null,
            "yesterday");

    ValidationResult result = validator.validate(action);

    assertTrue(result.hasRequirement("RG-ACT-001"));
    assertTrue(result.hasRequirement("RG-ACT-003"));
    assertFalse(result.isValid());
  }
}
