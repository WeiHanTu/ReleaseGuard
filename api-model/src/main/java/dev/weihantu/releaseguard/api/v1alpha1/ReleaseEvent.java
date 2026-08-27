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
@Kind("ReleaseEvent")
@Plural("releaseevents")
@Singular("releaseevent")
@ShortNames("re")
@AdditionalPrinterColumn(name = "Type", jsonPath = ".spec.type")
@AdditionalPrinterColumn(
    name = "Sequence",
    jsonPath = ".spec.sequence",
    type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(name = "Phase", jsonPath = ".status.phase")
public final class ReleaseEvent extends CustomResource<ReleaseEventSpec, ReleaseEventStatus>
    implements Namespaced {
  @Override
  @Required
  public ReleaseEventSpec getSpec() {
    return super.getSpec();
  }
}
