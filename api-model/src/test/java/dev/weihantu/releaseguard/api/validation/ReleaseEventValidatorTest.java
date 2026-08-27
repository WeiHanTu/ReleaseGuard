package dev.weihantu.releaseguard.api.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventSpec;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseEventValidatorTest {
  @Test
  void validatesDurableOutboxIdentity() {
    ReleaseEventSpec event =
        new ReleaseEventSpec(
            "sha256:" + TestFixtures.DIGEST,
            "4f8f:4",
            12L,
            "rollout.step.passed",
            "2026-08-26T21:00:01Z",
            Map.of("step", 1));

    assertTrue(new ReleaseEventValidator().validate(event).isValid());
  }

  @Test
  void rejectsMutableOrAmbiguousEventIdentity() {
    ReleaseEventSpec event =
        new ReleaseEventSpec("random", "checkout", 0L, "Step Passed", "later", null);

    ValidationResult result = new ReleaseEventValidator().validate(event);

    assertTrue(result.hasRequirement("RG-OUT-001"));
    assertTrue(result.hasRequirement("RG-ID-003"));
  }
}
