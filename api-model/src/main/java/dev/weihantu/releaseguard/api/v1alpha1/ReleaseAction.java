package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.generator.annotation.Required;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("delivery.weihantu.dev")
@Version("v1alpha1")
@Kind("ReleaseAction")
@Plural("releaseactions")
@Singular("releaseaction")
@ShortNames("ra")
@AdditionalPrinterColumn(name = "Type", jsonPath = ".spec.type")
@AdditionalPrinterColumn(name = "Phase", jsonPath = ".status.phase")
@AdditionalPrinterColumn(name = "Target", jsonPath = ".spec.target.name")
public final class ReleaseAction extends CustomResource<ReleaseActionSpec, ReleaseActionStatus>
    implements Namespaced {
  @Override
  @Required
  public ReleaseActionSpec getSpec() {
    return super.getSpec();
  }
}
