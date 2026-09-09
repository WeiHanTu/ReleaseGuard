package dev.weihantu.releaseguard.history;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionSpec;
import dev.weihantu.releaseguard.history.service.ActionSubmissionException;
import dev.weihantu.releaseguard.history.service.Fabric8ReleaseActionService;
import org.junit.jupiter.api.Test;

class Fabric8ReleaseActionServiceTest {
  @Test
  void unavailableKubernetesApiCannotReturnAFalseAcceptedMutation() {
    Fabric8ReleaseActionService service = new Fabric8ReleaseActionService(null);

    assertThrows(
        ActionSubmissionException.class,
        () ->
            service.submitAction(
                "demo",
                "checkout",
                "uid-checkout",
                1L,
                ReleaseActionSpec.Type.Pause,
                "manual review",
                "idempotency-key",
                "operator@example.com"));
  }
}
