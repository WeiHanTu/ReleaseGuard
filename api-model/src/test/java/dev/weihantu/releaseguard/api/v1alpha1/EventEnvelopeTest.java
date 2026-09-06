package dev.weihantu.releaseguard.api.v1alpha1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {
  @Test
  void constructsEnvelopeFromReleaseEventAndPlan() {
    ReleaseEvent event = new ReleaseEvent();
    event.setMetadata(
        new ObjectMetaBuilder()
            .withNamespace("demo")
            .withName("checkout-4-000012")
            .withLabels(Map.of("delivery.weihantu.dev/release-name", "checkout"))
            .build());
    event.setSpec(
        new ReleaseEventSpec(
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "uid-4f8f:4",
            12L,
            "rollout.step.passed",
            "2026-08-26T21:00:01Z",
            Map.of("step", 2, "weight", 50)));

    ReleasePlan plan = new ReleasePlan();
    plan.setMetadata(
        new ObjectMetaBuilder()
            .withNamespace("demo")
            .withName("checkout")
            .withUid("uid-4f8f")
            .withAnnotations(Map.of("delivery.weihantu.dev/git-revision", "abc12345"))
            .build());

    EventEnvelope envelope = EventEnvelope.from(event, plan);

    assertEquals(1, envelope.schemaVersion());
    assertEquals(
        "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        envelope.eventId());
    assertEquals("rollout.step.passed", envelope.type());
    assertEquals("uid-4f8f:4", envelope.releaseId());
    assertEquals("uid-4f8f", envelope.releaseUid());
    assertEquals(4L, envelope.generation());
    assertEquals(12L, envelope.sequence());
    assertEquals("demo", envelope.namespace());
    assertEquals("checkout", envelope.releaseName());
    assertEquals("abc12345", envelope.gitRevision());
    assertEquals("2026-08-26T21:00:01Z", envelope.occurredAt());
    assertEquals(2, envelope.payload().get("step"));

    String json = Serialization.asJson(envelope);
    assertNotNull(json);
    assertTrue(json.contains("\"schemaVersion\":1"));
    assertTrue(json.contains("\"eventId\":\"sha256:"));
    assertTrue(json.contains("\"releaseUid\":\"uid-4f8f\""));
  }
}
