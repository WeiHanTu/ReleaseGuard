package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
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
  void usesImmutableSelectorsAndGatesEveryCanaryStep() {
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
    assertEquals(600, rolloutSpec.get("progressDeadlineSeconds"));
    Map<?, ?> strategy = map(rolloutSpec.get("strategy"));
    Map<?, ?> canary = map(strategy.get("canary"));
    List<?> steps = list(canary.get("steps"));
    assertEquals(5, steps.size());
    assertEquals(5, map(steps.get(0)).get("setWeight"));
    assertEquals(25, map(steps.get(2)).get("setWeight"));
    assertEquals(100, map(steps.get(4)).get("setWeight"));
    assertFalse(steps.stream().anyMatch(step -> map(step).containsKey("pause")));

    Map<?, ?> firstGate = map(map(steps.get(1)).get("analysis"));
    assertEquals(
        "checkout-gates-g1", map(list(firstGate.get("templates")).getFirst()).get("templateName"));
    assertEquals(
        List.of(
            Map.of("name", "step", "value", "0"),
            Map.of("name", "mode", "value", "Canary"),
            Map.of(
                "name", "stable-revision", "valueFrom", Map.of("podTemplateHashValue", "Stable")),
            Map.of(
                "name", "canary-revision", "valueFrom", Map.of("podTemplateHashValue", "Latest"))),
        firstGate.get("args"));
    assertEquals(
        "1", map(list(map(map(steps.get(3)).get("analysis")).get("args")).getFirst()).get("value"));
  }

  @Test
  void terminalPromotionStepFollowsEveryDeclaredGate() {
    var plan = OperatorTestFixtures.plan(3);

    GenericKubernetesResource rollout =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer.render(plan, OperatorTestFixtures.policies()).getLast());

    List<?> steps =
        list(
            map(map(map(rollout.getAdditionalProperties().get("spec")).get("strategy"))
                    .get("canary"))
                .get("steps"));
    assertEquals(100, map(steps.getLast()).get("setWeight"));
    assertFalse(map(steps.getLast()).containsKey("analysis"));
    assertEquals(2, steps.stream().filter(step -> map(step).containsKey("analysis")).count());
    assertEquals(steps.size(), ReleaseResourceRenderer.argoStepCount(plan.getSpec()));
    assertEquals(0, ReleaseResourceRenderer.releaseStep(plan.getSpec(), 1));
    assertEquals(1, ReleaseResourceRenderer.releaseStep(plan.getSpec(), 3));
    assertEquals(2, ReleaseResourceRenderer.releaseStep(plan.getSpec(), 4));
    assertEquals(2, ReleaseResourceRenderer.releaseStep(plan.getSpec(), 9));
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
    assertEquals(10, metric.get("count"));
    assertEquals(3, metric.get("failureLimit"));
    assertEquals(2, metric.get("consecutiveSuccessLimit"));
    assertEquals(3, metric.get("inconclusiveLimit"));
    assertEquals(3, metric.get("consecutiveErrorLimit"));
    assertEquals("20s", metric.get("interval"));
    assertEquals(
        OperatorTestFixtures.policy().window(),
        metric.get("initialDelay"),
        "the first measurement must observe a full post-shift window");
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

  @Test
  void bootstrapAnalysisRunEvaluatesAbsoluteGatesWithoutABaseline() {
    GenericKubernetesResource run =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer
                .renderBootstrapAnalysis(
                    OperatorTestFixtures.plan(9), OperatorTestFixtures.policies(), "candidate-hash")
                .getFirst());

    assertEquals("AnalysisRun", run.getKind());
    assertEquals("checkout-bootstrap-g9", run.getMetadata().getName());
    assertEquals(
        OperatorTestFixtures.UID, run.getMetadata().getOwnerReferences().getFirst().getUid());

    Map<?, ?> spec = map(run.getAdditionalProperties().get("spec"));
    Map<String, Object> arguments = new LinkedHashMap<>();
    for (Object argument : list(spec.get("args"))) {
      Map<?, ?> entry = map(argument);
      arguments.put(entry.get("name").toString(), entry.get("value"));
    }
    assertEquals("0", arguments.get("step"));
    assertEquals("Bootstrap", arguments.get("mode"));
    assertEquals("", arguments.get("stable-revision"), "bootstrap must not carry a baseline");
    assertEquals("candidate-hash", arguments.get("canary-revision"));
    Map<?, ?> tokenArgument = map(list(spec.get("args")).getFirst());
    assertEquals("authorization-token", tokenArgument.get("name"));
    assertFalse(tokenArgument.containsKey("value"));

    List<?> metrics = list(spec.get("metrics"));
    assertEquals(1, metrics.size());
    Map<?, ?> metric = map(metrics.getFirst());
    assertEquals("gate-0-http-errors", metric.get("name"));
    assertEquals(10, metric.get("count"));
    assertEquals(OperatorTestFixtures.policy().window(), metric.get("initialDelay"));
    Map<?, ?> body = map(map(map(metric.get("provider")).get("web")).get("jsonBody"));
    assertEquals("{{ args.mode }}", body.get("mode"));
    assertEquals("817263", map(body.get("policy")).get("resourceVersion"));
  }

  @Test
  void bootstrapAnalysisSkipsBaselineOnlyGatesAndRefusesWithoutAnAbsoluteOne() {
    var mixed = OperatorTestFixtures.plan(9);
    mixed.setSpec(
        withGates(
            mixed.getSpec(),
            List.of(
                new ReleasePlanSpec.Gate("http-errors", 0.01, 0.005, 3, 2, 3),
                new ReleasePlanSpec.Gate("http-errors", null, 0.005, 3, 2, 3))));

    GenericKubernetesResource run =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer
                .renderBootstrapAnalysis(mixed, OperatorTestFixtures.policies(), "candidate-hash")
                .getFirst());
    List<?> metrics = list(map(run.getAdditionalProperties().get("spec")).get("metrics"));
    assertEquals(1, metrics.size());
    assertEquals("gate-0-http-errors", map(metrics.getFirst()).get("name"));

    var baselineOnly = OperatorTestFixtures.plan(9);
    baselineOnly.setSpec(
        withGates(
            baselineOnly.getSpec(),
            List.of(new ReleasePlanSpec.Gate("http-errors", null, 0.005, 3, 2, 3))));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            renderer.renderBootstrapAnalysis(
                baselineOnly, OperatorTestFixtures.policies(), "candidate-hash"));
  }

  @Test
  void rendersMeasurementCountBoundedByLongestStepDuration() {
    var plan = OperatorTestFixtures.plan(7);
    plan.setSpec(
        new ReleasePlanSpec(
            plan.getSpec().workload(),
            plan.getSpec().network(),
            new ReleasePlanSpec.Rollout(
                List.of(
                    new ReleasePlanSpec.Step(5, "3m"),
                    new ReleasePlanSpec.Step(25, "10m"),
                    new ReleasePlanSpec.Step(100, null)),
                plan.getSpec().rollout().gates()),
            plan.getSpec().failurePolicy(),
            plan.getSpec().deletionPolicy()));

    GenericKubernetesResource template =
        assertInstanceOf(
            GenericKubernetesResource.class,
            renderer.render(plan, OperatorTestFixtures.policies()).get(3));
    Map<?, ?> spec = map(template.getAdditionalProperties().get("spec"));
    Map<?, ?> metric = map(list(spec.get("metrics")).getFirst());
    // Longest step = 10m (600s), window = 2m (120s), interval = 20s -> 1 + (600 - 120) / 20 = 25
    assertEquals(25, metric.get("count"));
  }

  private static ReleasePlanSpec withGates(
      ReleasePlanSpec source, List<ReleasePlanSpec.Gate> gates) {
    return new ReleasePlanSpec(
        source.workload(),
        source.network(),
        new ReleasePlanSpec.Rollout(source.rollout().steps(), gates),
        source.failurePolicy(),
        source.deletionPolicy());
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
