package dev.weihantu.releaseguard.history.service;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseAction;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionSpec;

public interface ReleaseActionService {

  ReleaseAction submitAction(
      String namespace,
      String releaseName,
      String releaseUid,
      long generation,
      ReleaseActionSpec.Type type,
      String reason,
      String idempotencyKey,
      String actor);
}
