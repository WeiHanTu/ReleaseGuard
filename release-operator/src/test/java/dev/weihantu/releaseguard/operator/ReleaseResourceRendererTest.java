package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseResourceRendererTest {
  private final ReleaseResourceRenderer renderer = new ReleaseResourceRenderer();

  @Test
  void rendersDeterministicOwnedServicesIngressAndRollout() {
    var plan = OperatorTestFixtures.plan(7);

    List<HasMetadata> first = renderer.render(plan, OperatorTestFixtures.policies());
    List<HasMetadata> second = renderer.render(plan, OperatorTestFixtures.policies());

    assertEquals(first, second);
    assertEquals(
        List.of("Service", "Service", "Ingress", "AnalysisTemplate", "Rollout"),
        first.stream().map(HasMetadata::getKind).toList());
    assertEquals(
        List.of("checkout-stable", "checkout-canary", "checkout", "checkout-gates-g7", "checkout"),
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
            renderer
                .render(OperatorTestFixtures.plan(1), OperatorTestFixtures.policies())
                .getFirst());
    Map<?, ?> serviceSpec = map(stableService.getAdditionalProperties().get("spec"));
    Map<?, ?> serviceSelector = map(serviceSpec.get("selector"));
    assertFalse(serviceSelector.containsKey("app.kubernetes.io/name"));
    assertEquals(
        OperatorTestFixtures.UID, serviceSelector.get(ReleaseResourceRenderer.PLAN_UID_LABEL));
    assertFalse(serviceSelector.containsKey(ReleaseResourceRenderer.PLAN_GENERATION_LABEL));

    GenericKubernetesResource rollout =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer
                .render(OperatorTestFixtures.plan(1), OperatorTestFixtures.policies())
                .getLast());
    Map<?, ?> rolloutSpec = map(rollout.getAdditionalProperties().get("spec"));
    Map<?, ?> rolloutSelector = map(map(rolloutSpec.get("selector")).get("matchLabels"));
    assertFalse(rolloutSelector.containsKey(ReleaseResourceRenderer.PLAN_GENERATION_LABEL));
    Map<?, ?> podLabels = map(map(map(rolloutSpec.get("template")).get("metadata")).get("labels"));
    assertEquals("1", podLabels.get(ReleaseResourceRenderer.PLAN_GENERATION_LABEL));
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

  @Test
  void releaseGenerationChangesPodMetadataButNotLongLivedSelectors() {
    List<HasMetadata> generationOne =
        renderer.render(OperatorTestFixtures.plan(1), OperatorTestFixtures.policies());
    List<HasMetadata> generationTwo =
        renderer.render(OperatorTestFixtures.plan(2), OperatorTestFixtures.policies());

    GenericKubernetesResource serviceOneResource =
        assertInstanceOf(GenericKubernetesResource.class, generationOne.getFirst());
    GenericKubernetesResource serviceTwoResource =
        assertInstanceOf(GenericKubernetesResource.class, generationTwo.getFirst());
    Map<?, ?> serviceOne = map(serviceOneResource.getAdditionalProperties().get("spec"));
    Map<?, ?> serviceTwo = map(serviceTwoResource.getAdditionalProperties().get("spec"));
    assertEquals(serviceOne.get("selector"), serviceTwo.get("selector"));

    GenericKubernetesResource rolloutOneResource =
        assertInstanceOf(GenericKubernetesResource.class, generationOne.getLast());
    GenericKubernetesResource rolloutTwoResource =
        assertInstanceOf(GenericKubernetesResource.class, generationTwo.getLast());
    Map<?, ?> rolloutOne = map(rolloutOneResource.getAdditionalProperties().get("spec"));
    Map<?, ?> rolloutTwo = map(rolloutTwoResource.getAdditionalProperties().get("spec"));
    assertEquals(rolloutOne.get("selector"), rolloutTwo.get("selector"));
    Map<?, ?> podLabelsOne =
        map(map(map(rolloutOne.get("template")).get("metadata")).get("labels"));
    Map<?, ?> podLabelsTwo =
        map(map(map(rolloutTwo.get("template")).get("metadata")).get("labels"));
    assertEquals("1", podLabelsOne.get(ReleaseResourceRenderer.PLAN_GENERATION_LABEL));
    assertEquals("2", podLabelsTwo.get(ReleaseResourceRenderer.PLAN_GENERATION_LABEL));
  }

  @Test
  void snapshotsPolicyAndReferencesAuthenticationSecretWithoutRenderingTokenMaterial() {
    GenericKubernetesResource template =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer.render(OperatorTestFixtures.plan(7), OperatorTestFixtures.policies()).get(3));

    Map<?, ?> spec = map(template.getAdditionalProperties().get("spec"));
    List<?> args = list(spec.get("args"));
    Map<?, ?> tokenArg = map(args.getFirst());
    Map<?, ?> valueFrom = map(tokenArg.get("valueFrom"));
    Map<?, ?> secretKeyRef = map(valueFrom.get("secretKeyRef"));
    assertEquals("releaseguard-metric-gate-auth", secretKeyRef.get("name"));
    assertEquals("token", secretKeyRef.get("key"));
    assertFalse(tokenArg.containsKey("value"));

    Map<?, ?> metric = map(list(spec.get("metrics")).getFirst());
    assertEquals(3, metric.get("failureLimit"));
    assertEquals(2, metric.get("consecutiveSuccessLimit"));
    assertEquals(3, metric.get("inconclusiveLimit"));
    Map<?, ?> web = map(map(metric.get("provider")).get("web"));
    Map<?, ?> body = map(web.get("jsonBody"));
    Map<?, ?> policy = map(body.get("policy"));
    assertEquals("817263", policy.get("resourceVersion"));
    Map<?, ?> policySpec = map(policy.get("spec"));
    assertEquals(
        "sum(rate(requests{revision=\"{{ args.policy-placeholder-revision }}\"}"
            + "[{{ args.policy-placeholder-window }}]))",
        policySpec.get("valueQueryTemplate"));
    assertTrue(
        web.toString().contains("Bearer {{ args.authorization-token }}"),
        "the template must defer Secret resolution to Argo");
    assertFalse(body.containsKey("evaluationTime"));
  }

  @Test
  void preservesGatePromqlPlaceholdersAcrossOnePassArgoArgumentResolution() {
    GenericKubernetesResource template =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer.render(OperatorTestFixtures.plan(7), OperatorTestFixtures.policies()).get(3));
    Map<?, ?> spec = map(template.getAdditionalProperties().get("spec"));
    Map<String, String> arguments = new LinkedHashMap<>();
    arguments.put("authorization-token", "test-token");
    arguments.put("step", "0");
    arguments.put("mode", "Canary");
    arguments.put("stable-revision", "stable-hash");
    arguments.put("canary-revision", "canary-hash");
    for (Object argumentValue : list(spec.get("args"))) {
      Map<?, ?> argument = map(argumentValue);
      if (argument.get("value") instanceof String value) {
        arguments.put(argument.get("name").toString(), value);
      }
    }

    Map<?, ?> resolvedMetric = map(resolve(list(spec.get("metrics")).getFirst(), arguments));
    assertFalse(resolvedMetric.toString().contains("{{ args."));
    Map<?, ?> web = map(map(resolvedMetric.get("provider")).get("web"));
    Map<?, ?> policy = map(map(web.get("jsonBody")).get("policy"));
    Map<?, ?> policySpec = map(policy.get("spec"));
    assertEquals(
        OperatorTestFixtures.policy().valueQueryTemplate(), policySpec.get("valueQueryTemplate"));
    assertEquals(
        OperatorTestFixtures.policy().sampleCountQueryTemplate(),
        policySpec.get("sampleCountQueryTemplate"));
  }

  private static Map<?, ?> map(Object value) {
    return assertInstanceOf(Map.class, value);
  }

  private static List<?> list(Object value) {
    return assertInstanceOf(List.class, value);
  }

  private static Object resolve(Object value, Map<String, String> arguments) {
    if (value instanceof String string) {
      String resolved = string;
      for (Map.Entry<String, String> argument : arguments.entrySet()) {
        resolved = resolved.replace("{{ args." + argument.getKey() + " }}", argument.getValue());
      }
      return resolved;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> resolved = new LinkedHashMap<>();
      map.forEach((key, child) -> resolved.put(key.toString(), resolve(child, arguments)));
      return Map.copyOf(resolved);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(child -> resolve(child, arguments)).toList();
    }
    return value;
  }
}
