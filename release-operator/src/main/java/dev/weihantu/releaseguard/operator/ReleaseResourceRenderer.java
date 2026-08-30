package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReleaseResourceRenderer {
  public static final String API_VERSION = "delivery.weihantu.dev/v1alpha1";
  public static final String FIELD_MANAGER = "releaseguard-operator";
  public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
  public static final String PLAN_NAME_LABEL = "delivery.weihantu.dev/plan-name";
  public static final String PLAN_UID_LABEL = "delivery.weihantu.dev/plan-uid";
  public static final String PLAN_GENERATION_LABEL = "delivery.weihantu.dev/plan-generation";
  public static final String REVISION_LABEL = "delivery.weihantu.dev/revision";

  public List<HasMetadata> render(ReleasePlan plan) {
    Identity identity = Identity.from(plan);
    ReleasePlanSpec spec = Objects.requireNonNull(plan.getSpec(), "plan.spec");
    Map<String, String> selectorLabels = selectorLabels(identity);
    String stableService = identity.name() + "-stable";
    String canaryService = identity.name() + "-canary";

    return List.of(
        service(identity, stableService, spec, selectorLabels),
        service(identity, canaryService, spec, selectorLabels),
        ingress(identity, spec, stableService),
        rollout(identity, spec, stableService, canaryService, selectorLabels));
  }

  private static GenericKubernetesResource service(
      Identity identity, String name, ReleasePlanSpec spec, Map<String, String> selectorLabels) {
    Map<String, Object> port = new LinkedHashMap<>();
    port.put("name", "http");
    port.put("port", spec.network().servicePort());
    port.put("targetPort", spec.network().targetPort());

    Map<String, Object> serviceSpec = new LinkedHashMap<>();
    serviceSpec.put("type", "ClusterIP");
    serviceSpec.put("selector", selectorLabels);
    serviceSpec.put("ports", List.of(port));

    return resource("v1", "Service", metadata(identity, name), serviceSpec);
  }

  private static GenericKubernetesResource ingress(
      Identity identity, ReleasePlanSpec spec, String stableService) {
    Map<String, Object> servicePort = Map.of("number", spec.network().servicePort());
    Map<String, Object> service = Map.of("name", stableService, "port", servicePort);
    Map<String, Object> backend = Map.of("service", service);
    Map<String, Object> path =
        Map.of("path", spec.network().ingress().path(), "pathType", "Prefix", "backend", backend);
    Map<String, Object> rule =
        Map.of("host", spec.network().ingress().host(), "http", Map.of("paths", List.of(path)));

    Map<String, Object> ingressSpec = new LinkedHashMap<>();
    ingressSpec.put("ingressClassName", spec.network().ingress().className());
    ingressSpec.put("rules", List.of(rule));

    return resource(
        "networking.k8s.io/v1", "Ingress", metadata(identity, identity.name()), ingressSpec);
  }

  private static GenericKubernetesResource rollout(
      Identity identity,
      ReleasePlanSpec spec,
      String stableService,
      String canaryService,
      Map<String, String> selectorLabels) {
    Map<String, String> podLabels = new LinkedHashMap<>();
    PodTemplateSpec sourceTemplate = spec.workload().podTemplate();
    if (sourceTemplate.getMetadata() != null && sourceTemplate.getMetadata().getLabels() != null) {
      podLabels.putAll(sourceTemplate.getMetadata().getLabels());
    }
    podLabels.putAll(selectorLabels);
    podLabels.put(PLAN_GENERATION_LABEL, Long.toString(identity.generation()));
    podLabels.put(REVISION_LABEL, revision(identity, spec));

    Map<String, Object> template = new LinkedHashMap<>();
    template.put("metadata", Map.of("labels", Map.copyOf(podLabels)));
    template.put("spec", sourceTemplate.getSpec());

    List<Map<String, Object>> steps = new ArrayList<>();
    for (ReleasePlanSpec.Step step : spec.rollout().steps()) {
      steps.add(Map.of("setWeight", step.weight()));
      if (step.weight() < 100) {
        // AnalysisRun integration is not enabled yet. An indefinite pause is fail-closed until
        // then.
        steps.add(Map.of("pause", Map.of()));
      }
    }

    Map<String, Object> canary = new LinkedHashMap<>();
    canary.put("stableService", stableService);
    canary.put("canaryService", canaryService);
    canary.put("trafficRouting", Map.of("nginx", Map.of("stableIngress", identity.name())));
    canary.put("steps", List.copyOf(steps));

    Map<String, Object> rolloutSpec = new LinkedHashMap<>();
    rolloutSpec.put("replicas", spec.workload().replicas());
    rolloutSpec.put("selector", Map.of("matchLabels", selectorLabels));
    rolloutSpec.put("template", template);
    rolloutSpec.put("strategy", Map.of("canary", canary));

    return resource(
        "argoproj.io/v1alpha1", "Rollout", metadata(identity, identity.name()), rolloutSpec);
  }

  private static GenericKubernetesResource resource(
      String apiVersion, String kind, ObjectMeta metadata, Map<String, Object> spec) {
    GenericKubernetesResource resource = new GenericKubernetesResource();
    resource.setApiVersion(apiVersion);
    resource.setKind(kind);
    resource.setMetadata(metadata);
    resource.setAdditionalProperty("spec", spec);
    return resource;
  }

  private static ObjectMeta metadata(Identity identity, String name) {
    return new ObjectMetaBuilder()
        .withName(name)
        .withNamespace(identity.namespace())
        .withLabels(resourceLabels(identity))
        .withOwnerReferences(ownerReference(identity))
        .build();
  }

  private static OwnerReference ownerReference(Identity identity) {
    return new OwnerReferenceBuilder()
        .withApiVersion(API_VERSION)
        .withKind("ReleasePlan")
        .withName(identity.name())
        .withUid(identity.uid())
        .withController(true)
        .withBlockOwnerDeletion(true)
        .build();
  }

  private static Map<String, String> selectorLabels(Identity identity) {
    return Map.of(
        MANAGED_BY_LABEL,
        "releaseguard",
        PLAN_NAME_LABEL,
        identity.name(),
        PLAN_UID_LABEL,
        identity.uid());
  }

  private static Map<String, String> resourceLabels(Identity identity) {
    Map<String, String> labels = new LinkedHashMap<>(selectorLabels(identity));
    labels.put(PLAN_GENERATION_LABEL, Long.toString(identity.generation()));
    return Map.copyOf(labels);
  }

  static String revision(Identity identity, ReleasePlanSpec spec) {
    String image =
        spec.workload().podTemplate().getSpec().getContainers().stream()
            .filter(container -> spec.workload().managedContainer().equals(container.getName()))
            .findFirst()
            .orElseThrow()
            .getImage();
    int digestStart = image.indexOf("@sha256:");
    String suffix =
        digestStart >= 0 ? image.substring(digestStart + 8, digestStart + 20) : "mutable";
    return identity.name() + "-" + suffix;
  }

  record Identity(String name, String namespace, String uid, long generation) {
    static Identity from(ReleasePlan plan) {
      ObjectMeta metadata = Objects.requireNonNull(plan.getMetadata(), "plan.metadata");
      return new Identity(
          Objects.requireNonNull(metadata.getName(), "plan.metadata.name"),
          Objects.requireNonNull(metadata.getNamespace(), "plan.metadata.namespace"),
          Objects.requireNonNull(metadata.getUid(), "plan.metadata.uid"),
          Objects.requireNonNull(metadata.getGeneration(), "plan.metadata.generation"));
    }

    String releaseId() {
      return uid + ":" + generation;
    }
  }
}
