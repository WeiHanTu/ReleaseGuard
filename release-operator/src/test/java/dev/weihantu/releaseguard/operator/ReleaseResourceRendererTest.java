package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseResourceRendererTest {
  private final ReleaseResourceRenderer renderer = new ReleaseResourceRenderer();

  @Test
  void rendersDeterministicOwnedServicesIngressAndRollout() {
    var plan = OperatorTestFixtures.plan(7);

    List<HasMetadata> first = renderer.render(plan);
    List<HasMetadata> second = renderer.render(plan);

    assertEquals(first, second);
    assertEquals(
        List.of("Service", "Service", "Ingress", "Rollout"),
        first.stream().map(HasMetadata::getKind).toList());
    assertEquals(
        List.of("checkout-stable", "checkout-canary", "checkout", "checkout"),
        first.stream().map(resource -> resource.getMetadata().getName()).toList());

    for (HasMetadata resource : first) {
      assertEquals("demo", resource.getMetadata().getNamespace());
      assertEquals(
          "releaseguard", resource.getMetadata().getLabels().get("app.kubernetes.io/managed-by"));
      assertEquals(
          "7",
          resource.getMetadata().getLabels().get(ReleaseResourceRenderer.PLAN_GENERATION_LABEL));
      assertEquals(1, resource.getMetadata().getOwnerReferences().size());
      assertEquals(
          OperatorTestFixtures.UID,
          resource.getMetadata().getOwnerReferences().getFirst().getUid());
      assertTrue(resource.getMetadata().getOwnerReferences().getFirst().getController());
    }
  }

  @Test
  void usesImmutableSelectorsAndFailClosedPauses() {
    GenericKubernetesResource stableService =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer.render(OperatorTestFixtures.plan(1)).getFirst());
    Map<?, ?> serviceSpec = map(stableService.getAdditionalProperties().get("spec"));
    Map<?, ?> serviceSelector = map(serviceSpec.get("selector"));
    assertFalse(serviceSelector.containsKey("app.kubernetes.io/name"));
    assertEquals(
        OperatorTestFixtures.UID, serviceSelector.get(ReleaseResourceRenderer.PLAN_UID_LABEL));

    GenericKubernetesResource rollout =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer.render(OperatorTestFixtures.plan(1)).getLast());
    Map<?, ?> rolloutSpec = map(rollout.getAdditionalProperties().get("spec"));
    Map<?, ?> strategy = map(rolloutSpec.get("strategy"));
    Map<?, ?> canary = map(strategy.get("canary"));
    List<?> steps = list(canary.get("steps"));
    assertEquals(5, steps.size());
    assertEquals(5, map(steps.get(0)).get("setWeight"));
    assertTrue(map(map(steps.get(1)).get("pause")).isEmpty());
    assertEquals(25, map(steps.get(2)).get("setWeight"));
    assertTrue(map(map(steps.get(3)).get("pause")).isEmpty());
    assertEquals(100, map(steps.get(4)).get("setWeight"));
  }

  private static Map<?, ?> map(Object value) {
    return assertInstanceOf(Map.class, value);
  }

  private static List<?> list(Object value) {
    return assertInstanceOf(List.class, value);
  }
}
