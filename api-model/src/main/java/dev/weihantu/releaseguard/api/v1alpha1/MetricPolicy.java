package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.generator.annotation.Required;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("delivery.weihantu.dev")
@Version("v1alpha1")
@Kind("MetricPolicy")
@Plural("metricpolicies")
@Singular("metricpolicy")
@ShortNames("mp")
@AdditionalPrinterColumn(
    name = "Accepted",
    jsonPath = ".status.accepted",
    type = AdditionalPrinterColumn.Type.BOOLEAN)
@AdditionalPrinterColumn(name = "Direction", jsonPath = ".spec.direction")
public final class MetricPolicy extends CustomResource<MetricPolicySpec, MetricPolicyStatus> {
  @Override
  @Required
  public MetricPolicySpec getSpec() {
    return super.getSpec();
  }
}
