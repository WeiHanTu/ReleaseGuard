package dev.weihantu.releaseguard.history.api;

public record ActionResponse(
    String actionName,
    String namespace,
    String releaseName,
    String type,
    String phase,
    String idempotencyKey,
    String actor,
    String requestedAt) {}
