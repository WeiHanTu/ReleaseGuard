package dev.weihantu.releaseguard.history.service;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseAction;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionSpec;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class Fabric8ReleaseActionService implements ReleaseActionService {

  private static final Logger log = LoggerFactory.getLogger(Fabric8ReleaseActionService.class);

  private final KubernetesClient kubernetesClient;

  @Autowired
  public Fabric8ReleaseActionService(
      @Autowired(required = false) KubernetesClient kubernetesClient) {
    this.kubernetesClient = kubernetesClient;
  }

  @Override
  public ReleaseAction submitAction(
      String namespace,
      String releaseName,
      String releaseUid,
      long generation,
      ReleaseActionSpec.Type type,
      String reason,
      String idempotencyKey,
      String actor) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(releaseName, "releaseName");
    Objects.requireNonNull(releaseUid, "releaseUid");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(actor, "actor");

    String actionName =
        releaseName.toLowerCase() + "-" + type.name().toLowerCase() + "-" + hashKey(idempotencyKey);

    ReleaseAction action = new ReleaseAction();
    action.setMetadata(
        new ObjectMetaBuilder()
            .withName(actionName)
            .withNamespace(namespace)
            .withLabels(
                Map.of(
                    "delivery.weihantu.dev/release-name",
                    releaseName,
                    "app.kubernetes.io/managed-by",
                    "releaseguard"))
            .build());

    ReleaseActionSpec.Target target =
        new ReleaseActionSpec.Target(releaseName, releaseUid, generation);
    ReleaseActionSpec spec =
        new ReleaseActionSpec(
            target, type, reason, idempotencyKey, actor, Instant.now().toString());
    action.setSpec(spec);

    if (kubernetesClient != null) {
      try {
        log.info(
            "Creating ReleaseAction {} for release {}/{} (type={})",
            actionName,
            namespace,
            releaseName,
            type);
        return kubernetesClient
            .resources(ReleaseAction.class)
            .inNamespace(namespace)
            .resource(action)
            .create();
      } catch (Exception e) {
        log.warn(
            "Failed to submit ReleaseAction to Kubernetes API (falling back to generated resource): {}",
            e.getMessage());
      }
    }
    return action;
  }

  private static String hashKey(String key) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 4);
    } catch (NoSuchAlgorithmException e) {
      return Integer.toHexString(key.hashCode());
    }
  }
}
