package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

class ReleaseEventRendererTest {
  @Test
  void rendersDeterministicEventNameAndSpec() {
    ReleasePlan plan = new ReleasePlan();
    plan.setMetadata(
        new ObjectMetaBuilder()
            .withNamespace("demo")
            .withName("checkout")
            .withUid("uid-4f8f")
            .withGeneration(4L)
            .build());

    ReleasePlanStatus.Transition transition =
        new ReleasePlanStatus.Transition(
            12L,
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "rollback.started",
            "2026-08-26T21:00:01Z");

    ReleasePlanStatus status =
        new ReleasePlanStatus(
            4L,
            "uid-4f8f:4",
            Phase.RollingBack,
            2,
            "checkout-stable-hash",
            "checkout-canary-hash",
            null,
            new ReleasePlanStatus.GateDecision(
                "Failed", "AnalysisRun/run-1", "2026-08-26T21:00:00Z"),
            transition,
            0,
            java.util.List.of());

    ReleaseEvent event = ReleaseEventRenderer.render(plan, transition, status);

    assertEquals("checkout-4-000012", event.getMetadata().getName());
    assertEquals("demo", event.getMetadata().getNamespace());
    assertEquals(1, event.getMetadata().getOwnerReferences().size());
    assertEquals("ReleasePlan", event.getMetadata().getOwnerReferences().getFirst().getKind());
    assertEquals("checkout", event.getMetadata().getOwnerReferences().getFirst().getName());
    assertEquals("uid-4f8f", event.getMetadata().getOwnerReferences().getFirst().getUid());

    assertEquals(transition.eventId(), event.getSpec().eventId());
    assertEquals("uid-4f8f:4", event.getSpec().releaseId());
    assertEquals(12L, event.getSpec().sequence());
    assertEquals("rollback.started", event.getSpec().type());
    assertEquals("2026-08-26T21:00:01Z", event.getSpec().occurredAt());

    assertNotNull(event.getSpec().payload());
    assertEquals("RollingBack", event.getSpec().payload().get("phase"));
    assertEquals("checkout-stable-hash", event.getSpec().payload().get("stableRevision"));
    assertEquals("checkout-canary-hash", event.getSpec().payload().get("canaryRevision"));

    assertNotNull(event.getStatus());
    assertEquals(ReleaseEventStatus.Phase.Pending, event.getStatus().phase());
    assertEquals(0, event.getStatus().attempts());
  }

  @Test
  void eventNameFormatsSequenceWithSixDigits() {
    assertEquals("svc-1-000001", ReleaseEventRenderer.eventName("svc", 1L, 1L));
    assertEquals("svc-2-000042", ReleaseEventRenderer.eventName("svc", 2L, 42L));
    assertEquals("checkout-10-123456", ReleaseEventRenderer.eventName("checkout", 10L, 123456L));
  }
}
