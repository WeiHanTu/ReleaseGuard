package dev.weihantu.releaseguard.cli;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.validation.MetricPolicyValidator;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidationContext;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidator;
import dev.weihantu.releaseguard.api.validation.ValidationError;
import dev.weihantu.releaseguard.api.validation.ValidationResult;
import dev.weihantu.releaseguard.operator.MetricPolicySnapshot;
import dev.weihantu.releaseguard.operator.ReleaseResourceRenderer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class PlanProcessor {
  private static final KubernetesSerialization KUBERNETES = new KubernetesSerialization();
  private final ReleasePlanValidator planValidator = new ReleasePlanValidator();
  private final MetricPolicyValidator policyValidator = new MetricPolicyValidator();
  private final ReleaseResourceRenderer renderer = new ReleaseResourceRenderer();

  Evaluation validate(ManifestLoader.LoadedPlan loaded) {
    List<ValidationError> errors = new ArrayList<>();
    Map<String, MetricPolicySpec> acceptedPolicies = new LinkedHashMap<>();
    Set<String> referencedPolicies = referencedPolicies(loaded.plan());
    loaded.policies().entrySet().stream()
        .filter(entry -> referencedPolicies.contains(entry.getKey()))
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              ValidationResult result = policyValidator.validate(entry.getValue().getSpec());
              result.errors().stream()
                  .map(
                      error ->
                          new ValidationError(
                              error.requirement(),
                              "policies[" + entry.getKey() + "]." + error.path(),
                              error.message()))
                  .forEach(errors::add);
              if (result.isValid()) {
                acceptedPolicies.put(entry.getKey(), entry.getValue().getSpec());
              }
            });

    ReleasePlan plan = loaded.plan();
    String namespace = metadataValue(plan, true);
    if (namespace == null || namespace.isBlank()) {
      errors.add(
          new ValidationError(
              "RG-PLAN-001", "metadata.namespace", "namespace is required for ReleasePlan"));
      namespace = "default";
    }
    String name = metadataValue(plan, false);
    if (name == null || name.isBlank()) {
      errors.add(
          new ValidationError("RG-PLAN-001", "metadata.name", "name is required for ReleasePlan"));
    }
    errors.addAll(
        planValidator
            .validate(
                plan.getSpec(),
                ReleasePlanValidationContext.releaseEnvironment(namespace, acceptedPolicies))
            .errors());
    return new Evaluation(
        displayName(namespace, name), acceptedPolicies.size(), ValidationResult.from(errors));
  }

  private static Set<String> referencedPolicies(ReleasePlan plan) {
    if (plan.getSpec() == null
        || plan.getSpec().rollout() == null
        || plan.getSpec().rollout().gates() == null) {
      return Set.of();
    }
    return plan.getSpec().rollout().gates().stream()
        .filter(Objects::nonNull)
        .map(gate -> gate.policyRef())
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  RenderedPlan render(
      ManifestLoader.LoadedPlan loaded, String requestedUid, Long requestedGeneration)
      throws CliException {
    ReleasePlan plan = KUBERNETES.clone(loaded.plan());
    String namespace = metadataValue(plan, true);
    String name = metadataValue(plan, false);
    String uid = requestedUid;
    boolean syntheticUid = false;
    if (uid == null) {
      uid = plan.getMetadata().getUid();
    }
    if (uid == null || uid.isBlank()) {
      uid = previewUid(namespace, name);
      syntheticUid = true;
    }
    long generation =
        requestedGeneration != null
            ? requestedGeneration
            : plan.getMetadata().getGeneration() == null ? 1L : plan.getMetadata().getGeneration();
    plan.getMetadata().setUid(uid);
    plan.getMetadata().setGeneration(generation);
    try {
      Map<String, MetricPolicySnapshot> snapshots = new LinkedHashMap<>();
      loaded
          .policies()
          .forEach(
              (policyName, policy) -> {
                String resourceVersion = policy.getMetadata().getResourceVersion();
                snapshots.put(
                    policyName,
                    new MetricPolicySnapshot(
                        resourceVersion == null || resourceVersion.isBlank()
                            ? "offline-preview"
                            : resourceVersion,
                        policy.getSpec()));
              });
      return new RenderedPlan(renderer.render(plan, snapshots), uid, generation, syntheticUid);
    } catch (RuntimeException exception) {
      throw new CliException(ExitCode.INPUT, "cannot render plan: " + exception.getMessage());
    }
  }

  String yaml(List<HasMetadata> resources) throws CliException {
    List<String> documents = new ArrayList<>();
    try {
      for (HasMetadata resource : resources) {
        Map<?, ?> value = KUBERNETES.convertValue(resource, Map.class);
        documents.add(withoutDocumentMarker(KUBERNETES.asYaml(sorted(value)).strip()));
      }
    } catch (RuntimeException exception) {
      throw new CliException(ExitCode.INPUT, "cannot serialize rendered resources");
    }
    return String.join("\n---\n", documents) + "\n";
  }

  private static String withoutDocumentMarker(String yaml) {
    return yaml.startsWith("---\n") ? yaml.substring(4) : yaml;
  }

  String json(Object value) throws CliException {
    try {
      return KUBERNETES.asJson(sorted(value)) + "\n";
    } catch (RuntimeException exception) {
      throw new CliException(ExitCode.INPUT, "cannot serialize command output");
    }
  }

  private static Object sorted(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, child) -> sorted.put(key.toString(), sorted(child)));
      return sorted;
    }
    if (value instanceof Iterable<?> iterable) {
      List<Object> sorted = new ArrayList<>();
      iterable.forEach(child -> sorted.add(sorted(child)));
      return List.copyOf(sorted);
    }
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Enum<?>) {
      return value;
    }
    Map<?, ?> converted = KUBERNETES.convertValue(value, Map.class);
    return sorted(converted);
  }

  private static String metadataValue(ReleasePlan plan, boolean namespace) {
    if (plan.getMetadata() == null) {
      return null;
    }
    return namespace ? plan.getMetadata().getNamespace() : plan.getMetadata().getName();
  }

  private static String displayName(String namespace, String name) {
    String safeNamespace = namespace == null || namespace.isBlank() ? "<missing>" : namespace;
    String safeName = name == null || name.isBlank() ? "<missing>" : name;
    return safeNamespace + "/" + safeName;
  }

  private static String previewUid(String namespace, String name) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest((namespace + "/" + name).getBytes(StandardCharsets.UTF_8));
      return "offline-" + HexFormat.of().formatHex(hash, 0, 10);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  record Evaluation(String plan, int acceptedPolicyCount, ValidationResult result) {}

  record RenderedPlan(
      List<HasMetadata> resources, String uid, long generation, boolean syntheticUid) {}
}
