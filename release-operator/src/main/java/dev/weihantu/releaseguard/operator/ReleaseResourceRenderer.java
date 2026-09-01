package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.KubernetesDuration;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ReleaseResourceRenderer {
  public static final String API_VERSION = "delivery.weihantu.dev/v1alpha1";
  public static final String FIELD_MANAGER = "releaseguard-operator";
  public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
  public static final String PLAN_NAME_LABEL = "delivery.weihantu.dev/plan-name";
  public static final String PLAN_UID_LABEL = "delivery.weihantu.dev/plan-uid";
  public static final String PLAN_GENERATION_LABEL = "delivery.weihantu.dev/plan-generation";
  public static final String REVISION_LABEL = "delivery.weihantu.dev/revision";
  private static final String ANALYSIS_API_VERSION = "argoproj.io/v1alpha1";
  private static final String AUTHORIZATION_TOKEN_ARG = "authorization-token";
  private static final String STEP_ARG = "step";
  private static final String MODE_ARG = "mode";
  private static final String STABLE_REVISION_ARG = "stable-revision";
  private static final String CANARY_REVISION_ARG = "canary-revision";
  private static final String CANARY_MODE = "Canary";
  private static final String BOOTSTRAP_MODE = "Bootstrap";
  private static final int TERMINAL_WEIGHT = 100;
  private static final long ARGO_DEFAULT_PROGRESS_DEADLINE_SECONDS = 600L;
  private static final long PROGRESS_DEADLINE_SLACK_SECONDS = 300L;
  private static final List<Map.Entry<String, String>> POLICY_TEMPLATE_ARGS =
      List.of(
          Map.entry("revision", "policy-placeholder-revision"),
          Map.entry("namespace", "policy-placeholder-namespace"),
          Map.entry("releaseName", "policy-placeholder-release-name"),
          Map.entry("window", "policy-placeholder-window"));

  private final MetricGateAnalysisConfiguration analysisConfiguration;

  public ReleaseResourceRenderer() {
    this(MetricGateAnalysisConfiguration.DEFAULT);
  }

  public ReleaseResourceRenderer(MetricGateAnalysisConfiguration analysisConfiguration) {
    this.analysisConfiguration =
        Objects.requireNonNull(analysisConfiguration, "analysisConfiguration");
  }

  public List<HasMetadata> render(
      ReleasePlan plan, Map<String, MetricPolicySnapshot> policySnapshots) {
    Identity identity = Identity.from(plan);
    ReleasePlanSpec spec = Objects.requireNonNull(plan.getSpec(), "plan.spec");
    Objects.requireNonNull(policySnapshots, "policySnapshots");
    Map<String, String> selectorLabels = selectorLabels(identity);
    String stableService = identity.name() + "-stable";
    String canaryService = identity.name() + "-canary";

    return List.of(
        service(identity, stableService, spec, selectorLabels),
        service(identity, canaryService, spec, selectorLabels),
        ingress(identity, spec, stableService),
        analysisTemplate(identity, spec, policySnapshots),
        rollout(identity, spec, stableService, canaryService, selectorLabels));
  }

  private GenericKubernetesResource analysisTemplate(
      Identity identity,
      ReleasePlanSpec releaseSpec,
      Map<String, MetricPolicySnapshot> policySnapshots) {
    List<Map<String, Object>> metrics = new ArrayList<>();
    List<ReleasePlanSpec.Gate> gates = releaseSpec.rollout().gates();
    for (int index = 0; index < gates.size(); index++) {
      ReleasePlanSpec.Gate gate = gates.get(index);
      MetricPolicySnapshot snapshot = policySnapshots.get(gate.policyRef());
      if (snapshot == null) {
        throw new IllegalArgumentException(
            "accepted policy snapshot is missing for " + gate.policyRef());
      }
      metrics.add(analysisMetric(identity, gate, snapshot, index));
    }

    List<Map<String, Object>> args = new ArrayList<>();
    args.add(authorizationTokenArgument());
    args.add(Map.of("name", STEP_ARG));
    args.add(Map.of("name", MODE_ARG));
    args.add(Map.of("name", STABLE_REVISION_ARG));
    args.add(Map.of("name", CANARY_REVISION_ARG));
    args.addAll(policyPlaceholderArguments());

    Map<String, Object> templateSpec = new LinkedHashMap<>();
    templateSpec.put("args", List.copyOf(args));
    templateSpec.put("metrics", List.copyOf(metrics));
    return resource(
        ANALYSIS_API_VERSION,
        "AnalysisTemplate",
        metadata(identity, analysisTemplateName(identity)),
        templateSpec);
  }

  /**
   * Renders the bootstrap evaluation as a directly created AnalysisRun. Argo Rollouts skips canary
   * steps while no stable revision exists, so a first generation has no step that could run the
   * generated template, and RG-BOOT-002 still requires an administrator-approved absolute gate
   * before the candidate becomes stable.
   */
  public List<HasMetadata> renderBootstrapAnalysis(
      ReleasePlan plan,
      Map<String, MetricPolicySnapshot> policySnapshots,
      String candidateRevision) {
    Identity identity = Identity.from(plan);
    ReleasePlanSpec spec = Objects.requireNonNull(plan.getSpec(), "plan.spec");
    Objects.requireNonNull(policySnapshots, "policySnapshots");
    if (candidateRevision == null || candidateRevision.isBlank()) {
      throw new IllegalArgumentException("bootstrap analysis requires an observed revision");
    }

    List<Map<String, Object>> metrics = new ArrayList<>();
    List<ReleasePlanSpec.Gate> gates = spec.rollout().gates();
    for (int index = 0; index < gates.size(); index++) {
      ReleasePlanSpec.Gate gate = gates.get(index);
      if (gate.maxCanaryValue() == null) {
        // RG-GATE-011: a baseline-only gate has no meaning without a stable revision.
        continue;
      }
      MetricPolicySnapshot snapshot = policySnapshots.get(gate.policyRef());
      if (snapshot == null) {
        throw new IllegalArgumentException(
            "accepted policy snapshot is missing for " + gate.policyRef());
      }
      metrics.add(analysisMetric(identity, gate, snapshot, index));
    }
    if (metrics.isEmpty()) {
      throw new IllegalArgumentException("bootstrap analysis requires one absolute canary gate");
    }

    List<Map<String, Object>> args = new ArrayList<>();
    args.add(authorizationTokenArgument());
    args.add(Map.of("name", STEP_ARG, "value", "0"));
    args.add(Map.of("name", MODE_ARG, "value", BOOTSTRAP_MODE));
    // RG-GATE-011: bootstrap must not fabricate or reuse a baseline.
    args.add(Map.of("name", STABLE_REVISION_ARG, "value", ""));
    args.add(Map.of("name", CANARY_REVISION_ARG, "value", candidateRevision));
    args.addAll(policyPlaceholderArguments());

    Map<String, Object> runSpec = new LinkedHashMap<>();
    runSpec.put("args", List.copyOf(args));
    runSpec.put("metrics", List.copyOf(metrics));
    return List.of(
        resource(
            ANALYSIS_API_VERSION,
            "AnalysisRun",
            metadata(identity, bootstrapAnalysisName(identity)),
            runSpec));
  }

  private Map<String, Object> authorizationTokenArgument() {
    return Map.of(
        "name",
        AUTHORIZATION_TOKEN_ARG,
        "valueFrom",
        Map.of(
            "secretKeyRef",
            Map.of(
                "name",
                analysisConfiguration.authSecretName(),
                "key",
                analysisConfiguration.authSecretKey())));
  }

  private static List<Map<String, Object>> policyPlaceholderArguments() {
    return POLICY_TEMPLATE_ARGS.stream()
        .map(
            argument ->
                Map.<String, Object>of(
                    "name", argument.getValue(), "value", "{{" + argument.getKey() + "}}"))
        .toList();
  }

  private Map<String, Object> analysisMetric(
      Identity identity, ReleasePlanSpec.Gate gate, MetricPolicySnapshot snapshot, int index) {
    MetricPolicySpec policy = snapshot.spec();
    Map<String, Object> web = new LinkedHashMap<>();
    web.put("url", analysisConfiguration.evaluationUrl().toString());
    web.put("timeoutSeconds", timeoutSeconds(policy.timeout()));
    web.put("method", "POST");
    web.put(
        "headers",
        List.of(
            Map.of(
                "key",
                "Authorization",
                "value",
                "Bearer {{ args." + AUTHORIZATION_TOKEN_ARG + " }}")));
    web.put("jsonPath", "{$.decision}");
    web.put("jsonBody", evaluationBody(identity, gate, snapshot));

    Map<String, Object> metric = new LinkedHashMap<>();
    metric.put("name", metricName(index, gate.policyRef()));
    metric.put("interval", policy.interval());
    // The first measurement must observe a full post-shift window; a shorter delay would judge the
    // candidate on stable-only samples and burn the inconclusive budget.
    metric.put("initialDelay", policy.window());
    metric.put("failureLimit", gate.failureLimit());
    metric.put("consecutiveSuccessLimit", gate.consecutiveSuccesses());
    metric.put("inconclusiveLimit", gate.inconclusiveLimit());
    metric.put("consecutiveErrorLimit", gate.inconclusiveLimit());
    metric.put("successCondition", "result == \"Pass\"");
    metric.put("failureCondition", "result == \"Fail\"");
    metric.put("provider", Map.of("web", web));
    return Map.copyOf(metric);
  }

  private static Map<String, Object> evaluationBody(
      Identity identity, ReleasePlanSpec.Gate gate, MetricPolicySnapshot snapshot) {
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("name", gate.policyRef());
    policy.put("resourceVersion", snapshot.resourceVersion());
    policy.put("spec", policySpec(snapshot.spec()));

    Map<String, Object> thresholds = new LinkedHashMap<>();
    if (gate.maxCanaryValue() != null) {
      thresholds.put("maxCanaryValue", gate.maxCanaryValue());
    }
    if (gate.maxDeltaFromStable() != null) {
      thresholds.put("maxDeltaFromStable", gate.maxDeltaFromStable());
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("releaseId", identity.releaseId());
    body.put("step", "{{ args.step }}");
    body.put("mode", "{{ args.mode }}");
    body.put("namespace", identity.namespace());
    body.put("releaseName", identity.name());
    body.put("policy", Map.copyOf(policy));
    body.put("stableRevision", "{{ args.stable-revision }}");
    body.put("canaryRevision", "{{ args.canary-revision }}");
    body.put("thresholds", Map.copyOf(thresholds));
    return Map.copyOf(body);
  }

  private static Map<String, Object> policySpec(MetricPolicySpec spec) {
    Map<String, Object> allowedThresholds = new LinkedHashMap<>();
    allowedThresholds.put("canary", numericRange(spec.allowedThresholds().canary()));
    if (spec.allowedThresholds().stableDelta() != null) {
      allowedThresholds.put("stableDelta", numericRange(spec.allowedThresholds().stableDelta()));
    }

    Map<String, Object> value = new LinkedHashMap<>();
    value.put("direction", spec.direction().name());
    value.put("unit", spec.unit());
    value.put("valueQueryTemplate", policyTemplateForArgo(spec.valueQueryTemplate()));
    value.put("sampleCountQueryTemplate", policyTemplateForArgo(spec.sampleCountQueryTemplate()));
    value.put("allowedThresholds", Map.copyOf(allowedThresholds));
    value.put("minimumSamples", spec.minimumSamples());
    value.put("window", spec.window());
    value.put("interval", spec.interval());
    value.put("timeout", spec.timeout());
    return Map.copyOf(value);
  }

  private static Map<String, Object> numericRange(MetricPolicySpec.NumericRange range) {
    return Map.of("minimum", range.minimum(), "maximum", range.maximum());
  }

  private static String policyTemplateForArgo(String template) {
    String escaped = template;
    for (Map.Entry<String, String> entry : POLICY_TEMPLATE_ARGS) {
      escaped =
          escaped.replace("{{" + entry.getKey() + "}}", "{{ args." + entry.getValue() + " }}");
    }
    return escaped;
  }

  static MetricPolicySpec policySpecFromArgo(MetricPolicySpec spec) {
    return new MetricPolicySpec(
        spec.direction(),
        spec.unit(),
        policyTemplateFromArgo(spec.valueQueryTemplate()),
        policyTemplateFromArgo(spec.sampleCountQueryTemplate()),
        spec.allowedThresholds(),
        spec.minimumSamples(),
        spec.window(),
        spec.interval(),
        spec.timeout());
  }

  private static String policyTemplateFromArgo(String template) {
    String restored = template;
    for (Map.Entry<String, String> entry : POLICY_TEMPLATE_ARGS) {
      restored =
          restored.replace("{{ args." + entry.getValue() + " }}", "{{" + entry.getKey() + "}}");
    }
    return restored;
  }

  private static int timeoutSeconds(String value) {
    long milliseconds =
        KubernetesDuration.parse(value)
            .orElseThrow(() -> new IllegalArgumentException("invalid policy timeout: " + value))
            .toMillis();
    return Math.toIntExact(Math.max(1, (milliseconds + 999) / 1000));
  }

  private static String metricName(int index, String policyRef) {
    String sanitized =
        policyRef.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("^-+|-+$", "");
    if (sanitized.isBlank()) {
      sanitized = "policy";
    }
    String prefix = "gate-" + index + "-";
    return prefix + sanitized.substring(0, Math.min(sanitized.length(), 63 - prefix.length()));
  }

  static String analysisTemplateName(Identity identity) {
    return identity.name() + "-gates-g" + identity.generation();
  }

  static String bootstrapAnalysisName(Identity identity) {
    return identity.name() + "-bootstrap-g" + identity.generation();
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
    List<ReleasePlanSpec.Step> releaseSteps = spec.rollout().steps();
    for (int index = 0; index < releaseSteps.size(); index++) {
      steps.add(Map.of("setWeight", releaseSteps.get(index).weight()));
      if (releaseSteps.get(index).weight() < TERMINAL_WEIGHT) {
        steps.add(Map.of("analysis", gateAnalysis(identity, index)));
      }
    }

    Map<String, Object> canary = new LinkedHashMap<>();
    canary.put("stableService", stableService);
    canary.put("canaryService", canaryService);
    canary.put("trafficRouting", Map.of("nginx", Map.of("stableIngress", identity.name())));
    canary.put("steps", List.copyOf(steps));

    Map<String, Object> rolloutSpec = new LinkedHashMap<>();
    rolloutSpec.put("replicas", spec.workload().replicas());
    rolloutSpec.put("progressDeadlineSeconds", progressDeadlineSeconds(spec));
    rolloutSpec.put("selector", Map.of("matchLabels", selectorLabels));
    rolloutSpec.put("template", template);
    rolloutSpec.put("strategy", Map.of("canary", canary));

    return resource(
        ANALYSIS_API_VERSION, "Rollout", metadata(identity, identity.name()), rolloutSpec);
  }

  private static Map<String, Object> gateAnalysis(Identity identity, int releaseStep) {
    Map<String, Object> analysis = new LinkedHashMap<>();
    analysis.put("templates", List.of(Map.of("templateName", analysisTemplateName(identity))));
    analysis.put(
        "args",
        List.of(
            Map.of("name", STEP_ARG, "value", Integer.toString(releaseStep)),
            Map.of("name", MODE_ARG, "value", CANARY_MODE),
            Map.of(
                "name", STABLE_REVISION_ARG, "valueFrom", Map.of("podTemplateHashValue", "Stable")),
            Map.of(
                "name",
                CANARY_REVISION_ARG,
                "valueFrom",
                Map.of("podTemplateHashValue", "Latest"))));
    return Map.copyOf(analysis);
  }

  /** Counts the Argo steps rendered for a plan so observed indexes can be interpreted. */
  static int argoStepCount(ReleasePlanSpec spec) {
    int count = 0;
    for (ReleasePlanSpec.Step step : spec.rollout().steps()) {
      count++;
      if (step.weight() < TERMINAL_WEIGHT) {
        count++;
      }
    }
    return count;
  }

  /** Maps an observed Argo step index back to the declared ReleaseGuard step index. */
  static int releaseStep(ReleasePlanSpec spec, int argoStepIndex) {
    List<ReleasePlanSpec.Step> steps = spec.rollout().steps();
    int argoStep = 0;
    for (int index = 0; index < steps.size(); index++) {
      argoStep++;
      if (steps.get(index).weight() < TERMINAL_WEIGHT) {
        argoStep++;
      }
      if (argoStepIndex < argoStep) {
        return index;
      }
    }
    return steps.size() - 1;
  }

  private static int progressDeadlineSeconds(ReleasePlanSpec spec) {
    long longestStep =
        spec.rollout().steps().stream()
            .map(ReleasePlanSpec.Step::duration)
            .map(KubernetesDuration::parse)
            .flatMap(Optional::stream)
            .mapToLong(Duration::toSeconds)
            .max()
            .orElse(0L);
    return Math.toIntExact(
        Math.max(
            ARGO_DEFAULT_PROGRESS_DEADLINE_SECONDS, longestStep + PROGRESS_DEADLINE_SLACK_SECONDS));
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
