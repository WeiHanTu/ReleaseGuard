package dev.weihantu.releaseguard.api.validation;

import dev.weihantu.releaseguard.api.v1alpha1.KubernetesDuration;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ReleasePlanValidator {
  private static final Pattern DIGEST_IMAGE = Pattern.compile("^.+@sha256:[0-9a-f]{64}$");
  private static final Duration MINIMUM_STEP_DURATION = Duration.ofSeconds(10);
  private static final Duration MAXIMUM_STEP_DURATION = Duration.ofHours(24);
  private static final Pattern DNS_HOST =
      Pattern.compile(
          "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*"
              + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

  public ValidationResult validate(ReleasePlanSpec spec, ReleasePlanValidationContext context) {
    List<ValidationError> errors = new ArrayList<>();
    if (spec == null) {
      add(errors, "RG-PLAN-001", "spec", "spec is required");
      return ValidationResult.from(errors);
    }

    validateWorkload(spec.workload(), context, errors);
    validateNetwork(spec.network(), errors);
    validateRollout(spec.rollout(), context.acceptedPolicies(), errors);
    validateFailurePolicy(spec.failurePolicy(), errors);
    return ValidationResult.from(errors);
  }

  public ValidationResult validateUpdate(
      ReleasePlanSpec previous,
      ReleasePlanSpec proposed,
      ReleasePlanStatus.Phase currentPhase,
      ReleasePlanValidationContext context) {
    List<ValidationError> errors = new ArrayList<>(validate(proposed, context).errors());
    if (isActive(currentPhase) && !Objects.equals(previous, proposed)) {
      add(
          errors,
          "RG-PLAN-011",
          "spec",
          "rollout-affecting spec changes are forbidden while a release is active");
    }
    return ValidationResult.from(errors);
  }

  private static boolean isActive(ReleasePlanStatus.Phase phase) {
    return phase == ReleasePlanStatus.Phase.Validating
        || phase == ReleasePlanStatus.Phase.Bootstrapping
        || phase == ReleasePlanStatus.Phase.Progressing
        || phase == ReleasePlanStatus.Phase.Paused
        || phase == ReleasePlanStatus.Phase.RollingBack;
  }

  private static void validateWorkload(
      ReleasePlanSpec.Workload workload,
      ReleasePlanValidationContext context,
      List<ValidationError> errors) {
    if (workload == null) {
      add(errors, "RG-PLAN-001", "spec.workload", "workload is required");
      return;
    }
    if (workload.replicas() == null
        || workload.replicas() < context.minimumReplicas()
        || workload.replicas() > context.maximumReplicas()) {
      add(
          errors,
          "RG-PLAN-004",
          "spec.workload.replicas",
          "replicas must be within configured bounds "
              + context.minimumReplicas()
              + ".."
              + context.maximumReplicas());
    }

    PodTemplateSpec template = workload.podTemplate();
    PodSpec podSpec = template == null ? null : template.getSpec();
    List<Container> containers =
        podSpec == null || podSpec.getContainers() == null ? List.of() : podSpec.getContainers();
    List<Container> managed =
        containers.stream()
            .filter(container -> Objects.equals(container.getName(), workload.managedContainer()))
            .toList();
    if (managed.size() != 1) {
      add(
          errors,
          "RG-PLAN-001",
          "spec.workload.managedContainer",
          "managed container must exist exactly once in podTemplate.spec.containers");
    } else if (!context.allowMutableManagedImage()
        && !isDigestImage(managed.getFirst().getImage())) {
      add(
          errors,
          "RG-PLAN-002",
          "spec.workload.podTemplate.spec.containers[" + managed.getFirst().getName() + "].image",
          "managed container image must use a lowercase sha256 digest");
    }

    if (context.requireAllImagesByDigest()) {
      allContainers(podSpec)
          .filter(container -> !isDigestImage(container.getImage()))
          .forEach(
              container ->
                  add(
                      errors,
                      "RG-PLAN-003",
                      "spec.workload.podTemplate.spec.containers["
                          + container.getName()
                          + "].image",
                      "every release-environment image must use a lowercase sha256 digest"));
    }
    validatePodSecurity(podSpec, errors);
  }

  private static Stream<Container> allContainers(PodSpec podSpec) {
    if (podSpec == null) {
      return Stream.empty();
    }
    return Stream.concat(
        Optional.ofNullable(podSpec.getContainers()).orElseGet(List::of).stream(),
        Optional.ofNullable(podSpec.getInitContainers()).orElseGet(List::of).stream());
  }

  private static void validatePodSecurity(PodSpec podSpec, List<ValidationError> errors) {
    if (podSpec == null) {
      add(errors, "RG-PLAN-010", "spec.workload.podTemplate.spec", "pod spec is required");
      return;
    }
    if (Boolean.TRUE.equals(podSpec.getHostNetwork())
        || Boolean.TRUE.equals(podSpec.getHostPID())
        || Boolean.TRUE.equals(podSpec.getHostIPC())) {
      add(
          errors,
          "RG-PLAN-010",
          "spec.workload.podTemplate.spec",
          "host network, PID, and IPC namespaces are forbidden");
    }
    if (Optional.ofNullable(podSpec.getVolumes()).orElseGet(List::of).stream()
        .anyMatch(volume -> volume.getHostPath() != null)) {
      add(
          errors,
          "RG-PLAN-010",
          "spec.workload.podTemplate.spec.volumes",
          "hostPath volumes are forbidden");
    }
    allContainers(podSpec)
        .forEach(
            container -> {
              var security = container.getSecurityContext();
              if (security != null && Boolean.TRUE.equals(security.getPrivileged())) {
                add(
                    errors,
                    "RG-PLAN-010",
                    "spec.workload.podTemplate.spec.containers["
                        + container.getName()
                        + "].securityContext",
                    "privileged containers are forbidden");
              }
              if (security != null
                  && security.getCapabilities() != null
                  && security.getCapabilities().getAdd() != null
                  && !security.getCapabilities().getAdd().isEmpty()) {
                add(
                    errors,
                    "RG-PLAN-010",
                    "spec.workload.podTemplate.spec.containers["
                        + container.getName()
                        + "].securityContext.capabilities.add",
                    "adding Linux capabilities is forbidden");
              }
            });
  }

  private static void validateNetwork(
      ReleasePlanSpec.Network network, List<ValidationError> errors) {
    if (network == null || network.ingress() == null) {
      add(errors, "RG-PLAN-001", "spec.network", "network and ingress are required");
      return;
    }
    if (network.servicePort() == null
        || network.servicePort() < 1
        || network.servicePort() > 65535) {
      add(
          errors,
          "RG-PLAN-001",
          "spec.network.servicePort",
          "service port must be within 1..65535");
    }
    if (network.targetPort() == null) {
      add(errors, "RG-PLAN-001", "spec.network.targetPort", "target port is required");
    }
    if (!"nginx".equals(network.ingress().className())) {
      add(
          errors,
          "RG-PLAN-001",
          "spec.network.ingress.className",
          "v1 supports only nginx ingress");
    }
    if (network.ingress().host() == null || !DNS_HOST.matcher(network.ingress().host()).matches()) {
      add(errors, "RG-PLAN-001", "spec.network.ingress.host", "host must be a lowercase DNS name");
    }
    if (network.ingress().path() != null && !network.ingress().path().startsWith("/")) {
      add(errors, "RG-PLAN-001", "spec.network.ingress.path", "ingress path must start with '/'");
    }
  }

  private static void validateRollout(
      ReleasePlanSpec.Rollout rollout,
      Map<String, MetricPolicySpec> policies,
      List<ValidationError> errors) {
    if (rollout == null) {
      add(errors, "RG-PLAN-005", "spec.rollout", "rollout is required");
      return;
    }
    List<ReleasePlanSpec.Step> steps = rollout.steps() == null ? List.of() : rollout.steps();
    int previousWeight = 0;
    for (int index = 0; index < steps.size(); index++) {
      ReleasePlanSpec.Step step = steps.get(index);
      String path = "spec.rollout.steps[" + index + "]";
      if (step == null
          || step.weight() == null
          || step.weight() <= previousWeight
          || step.weight() > 100) {
        add(
            errors,
            "RG-PLAN-005",
            path + ".weight",
            "weights must strictly increase within 1..100");
        continue;
      }
      previousWeight = step.weight();
      boolean terminal = index == steps.size() - 1;
      if (!terminal) {
        Optional<Duration> duration = KubernetesDuration.parse(step.duration());
        if (duration.isEmpty()
            || duration.get().compareTo(MINIMUM_STEP_DURATION) < 0
            || duration.get().compareTo(MAXIMUM_STEP_DURATION) > 0) {
          add(
              errors,
              "RG-PLAN-006",
              path + ".duration",
              "nonterminal duration must be between 10s and 24h");
        }
      }
    }
    if (steps.isEmpty()
        || steps.getLast() == null
        || !Integer.valueOf(100).equals(steps.getLast().weight())) {
      add(errors, "RG-PLAN-005", "spec.rollout.steps", "the final traffic weight must be 100");
    }

    List<ReleasePlanSpec.Gate> gates = rollout.gates() == null ? List.of() : rollout.gates();
    if (gates.stream().filter(Objects::nonNull).noneMatch(gate -> gate.maxCanaryValue() != null)) {
      add(
          errors,
          "RG-PLAN-013",
          "spec.rollout.gates",
          "at least one absolute canary threshold is required for bootstrap");
    }
    for (int index = 0; index < gates.size(); index++) {
      validateGate(gates.get(index), index, policies, errors);
    }
  }

  private static void validateGate(
      ReleasePlanSpec.Gate gate,
      int index,
      Map<String, MetricPolicySpec> policies,
      List<ValidationError> errors) {
    String path = "spec.rollout.gates[" + index + "]";
    if (gate == null || gate.policyRef() == null || gate.policyRef().isBlank()) {
      add(errors, "RG-PLAN-007", path + ".policyRef", "policy reference is required");
      return;
    }
    MetricPolicySpec policy = policies.get(gate.policyRef());
    if (policy == null) {
      add(errors, "RG-PLAN-007", path + ".policyRef", "referenced policy is not accepted");
      return;
    }
    MetricPolicySpec.AllowedThresholds allowed = policy.allowedThresholds();
    validateThreshold(
        gate.maxCanaryValue(),
        allowed == null ? null : allowed.canary(),
        path + ".maxCanaryValue",
        errors);
    validateThreshold(
        gate.maxDeltaFromStable(),
        allowed == null ? null : allowed.stableDelta(),
        path + ".maxDeltaFromStable",
        errors);
    if (gate.consecutiveFailures() != null && gate.consecutiveFailures() < 1) {
      add(errors, "RG-PLAN-007", path + ".consecutiveFailures", "value must be positive");
    }
    if (gate.consecutiveSuccesses() != null && gate.consecutiveSuccesses() < 1) {
      add(errors, "RG-PLAN-007", path + ".consecutiveSuccesses", "value must be positive");
    }
  }

  private static void validateThreshold(
      Double value,
      MetricPolicySpec.NumericRange range,
      String path,
      List<ValidationError> errors) {
    if (value == null) {
      return;
    }
    if (!Double.isFinite(value)
        || range == null
        || range.minimum() == null
        || range.maximum() == null
        || value < range.minimum()
        || value > range.maximum()) {
      add(errors, "RG-POL-006", path, "threshold is outside administrator-approved bounds");
    }
  }

  private static void validateFailurePolicy(
      ReleasePlanSpec.FailurePolicy failurePolicy, List<ValidationError> errors) {
    if (failurePolicy == null
        || failurePolicy.metricUnavailable() != ReleasePlanSpec.MetricUnavailable.Pause
        || failurePolicy.gateFailed() != ReleasePlanSpec.GateFailed.Rollback
        || failurePolicy.auditBackpressure() != ReleasePlanSpec.AuditBackpressure.PausePromotion) {
      add(
          errors,
          "RG-PLAN-001",
          "spec.failurePolicy",
          "v1 requires Pause, Rollback, and PausePromotion safety behavior");
    }
    if (failurePolicy == null
        || KubernetesDuration.parse(failurePolicy.rollbackDeadline()).isEmpty()) {
      add(
          errors,
          "RG-PLAN-001",
          "spec.failurePolicy.rollbackDeadline",
          "rollback deadline must be a positive Kubernetes duration");
    }
  }

  private static boolean isDigestImage(String image) {
    return image != null && DIGEST_IMAGE.matcher(image).matches();
  }

  private static void add(
      List<ValidationError> errors, String requirement, String path, String message) {
    errors.add(new ValidationError(requirement, path, message));
  }
}
