package dev.weihantu.releaseguard.history.api;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
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
import io.fabric8.kubernetes.api.model.ObjectMeta;
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
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlanController {

  private static final KubernetesSerialization KUBERNETES = new KubernetesSerialization();

  private final ReleasePlanValidator planValidator = new ReleasePlanValidator();
  private final MetricPolicyValidator policyValidator = new MetricPolicyValidator();
  private final ReleaseResourceRenderer renderer = new ReleaseResourceRenderer();

  @PostMapping("/v1/plans:validate")
  public ResponseEntity<PlanValidationResponse> validatePlan(
      @RequestBody PlanValidationRequest request) {
    if (request == null || request.plan() == null) {
      throw new IllegalArgumentException("Plan is required in request body");
    }

    ReleasePlan plan = request.plan();
    List<ValidationError> errors = new ArrayList<>();
    Map<String, MetricPolicySpec> acceptedPolicies = new LinkedHashMap<>();
    Set<String> referencedPolicies = referencedPolicies(plan);

    for (MetricPolicy policy : request.policies()) {
      String policyName = policy.getMetadata() != null ? policy.getMetadata().getName() : null;
      if (policyName != null && referencedPolicies.contains(policyName)) {
        ValidationResult result = policyValidator.validate(policy.getSpec());
        result.errors().stream()
            .map(
                e ->
                    new ValidationError(
                        e.requirement(), "policies[" + policyName + "]." + e.path(), e.message()))
            .forEach(errors::add);
        if (result.isValid()) {
          acceptedPolicies.put(policyName, policy.getSpec());
        }
      }
    }

    String namespace = plan.getMetadata() != null ? plan.getMetadata().getNamespace() : null;
    if (namespace == null || namespace.isBlank()) {
      errors.add(
          new ValidationError(
              "RG-PLAN-001", "metadata.namespace", "namespace is required for ReleasePlan"));
      namespace = "default";
    }
    String name = plan.getMetadata() != null ? plan.getMetadata().getName() : null;
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

    List<PlanValidationResponse.ValidationErrorView> errorViews =
        errors.stream()
            .map(
                e ->
                    new PlanValidationResponse.ValidationErrorView(
                        e.requirement(), e.path(), e.message()))
            .toList();

    return ResponseEntity.ok(
        new PlanValidationResponse(errors.isEmpty(), acceptedPolicies.size(), errorViews));
  }

  @PostMapping("/v1/plans:render")
  public ResponseEntity<PlanRenderResponse> renderPlan(@RequestBody PlanRenderRequest request) {
    if (request == null || request.plan() == null) {
      throw new IllegalArgumentException("Plan is required in request body");
    }

    ReleasePlan plan = KUBERNETES.clone(request.plan());
    if (plan.getMetadata() == null) {
      plan.setMetadata(new ObjectMeta());
    }

    String namespace = plan.getMetadata().getNamespace();
    if (namespace == null || namespace.isBlank()) {
      namespace = "default";
      plan.getMetadata().setNamespace(namespace);
    }
    String name = plan.getMetadata().getName();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("metadata.name is required to render a ReleasePlan");
    }

    String uid = request.uid();
    if (uid == null || uid.isBlank()) {
      uid = plan.getMetadata().getUid();
    }
    if (uid == null || uid.isBlank()) {
      uid = previewUid(namespace, name);
    }
    long generation =
        request.generation() != null
            ? request.generation()
            : plan.getMetadata().getGeneration() != null ? plan.getMetadata().getGeneration() : 1L;

    plan.getMetadata().setUid(uid);
    plan.getMetadata().setGeneration(generation);

    Map<String, MetricPolicySnapshot> snapshots = new LinkedHashMap<>();
    for (MetricPolicy policy : request.policies()) {
      String policyName = policy.getMetadata() != null ? policy.getMetadata().getName() : null;
      if (policyName != null) {
        String resourceVersion =
            policy.getMetadata().getResourceVersion() != null
                ? policy.getMetadata().getResourceVersion()
                : "1";
        snapshots.put(policyName, new MetricPolicySnapshot(resourceVersion, policy.getSpec()));
      }
    }

    List<HasMetadata> resources = renderer.render(plan, snapshots);
    List<Map<String, Object>> serializedResources = new ArrayList<>(resources.size());
    for (HasMetadata res : resources) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = KUBERNETES.convertValue(res, Map.class);
      serializedResources.add(map);
    }

    return ResponseEntity.ok(
        new PlanRenderResponse(name, namespace, uid, generation, serializedResources));
  }

  private static Set<String> referencedPolicies(ReleasePlan plan) {
    if (plan.getSpec() == null
        || plan.getSpec().rollout() == null
        || plan.getSpec().rollout().gates() == null) {
      return Set.of();
    }
    return plan.getSpec().rollout().gates().stream()
        .filter(Objects::nonNull)
        .map(dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec.Gate::policyRef)
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String previewUid(String namespace, String name) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest((namespace + "/" + name).getBytes(StandardCharsets.UTF_8));
      return "preview-" + HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      return "preview-uid";
    }
  }
}
