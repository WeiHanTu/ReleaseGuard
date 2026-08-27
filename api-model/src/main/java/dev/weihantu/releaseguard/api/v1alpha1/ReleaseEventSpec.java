package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.crd.generator.annotation.PreserveUnknownFields;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import java.util.Map;

public record ReleaseEventSpec(
    @Required @Pattern("sha256:[0-9a-f]{64}") String eventId,
    @Required @Size(min = 3) String releaseId,
    @Required @Min(1) Long sequence,
    @Required @Size(min = 1) String type,
    @Required String occurredAt,
    @Required @PreserveUnknownFields Map<String, Object> payload) {}
