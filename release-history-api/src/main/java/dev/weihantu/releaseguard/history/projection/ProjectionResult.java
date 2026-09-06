package dev.weihantu.releaseguard.history.projection;

public record ProjectionResult(Status status, String eventId, String reason) {

  public enum Status {
    APPLIED,
    ALREADY_PROCESSED,
    QUARANTINED
  }

  public static ProjectionResult applied(String eventId) {
    return new ProjectionResult(Status.APPLIED, eventId, null);
  }

  public static ProjectionResult alreadyProcessed(String eventId) {
    return new ProjectionResult(
        Status.ALREADY_PROCESSED, eventId, "Event already processed previously");
  }

  public static ProjectionResult quarantined(String eventId, String reason) {
    return new ProjectionResult(Status.QUARANTINED, eventId, reason);
  }
}
