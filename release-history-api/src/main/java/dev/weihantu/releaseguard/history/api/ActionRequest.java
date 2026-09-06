package dev.weihantu.releaseguard.history.api;

public record ActionRequest(String reason, String uid, Long generation) {}
