package dev.weihantu.releaseguard.history.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AuditRepository {

  boolean eventExists(String eventId);

  void insertEvent(EventRecord event);

  Optional<ReleaseProjectionRecord> findReleaseById(String releaseId);

  Optional<ReleaseProjectionRecord> findLatestRelease(String namespace, String name);

  void upsertRelease(ReleaseProjectionRecord record);

  void deleteReleaseProjections();

  List<EventRecord> findEventsByRelease(
      String namespace, String name, Long afterSequence, int limit);

  List<EventRecord> findEventsByReleaseId(String releaseId, Long afterSequence, int limit);

  List<EventRecord> findAllEventsOrdered();

  void insertQuarantinedEvent(QuarantinedEventRecord quarantined);

  long countQuarantinedEvents(String releaseId);

  long countAllQuarantinedEvents();

  void upsertCheckpoint(String topic, int partitionId, long lastOffset, Instant updatedAt);

  Map<Integer, Long> getCheckpoints(String topic);

  Optional<Instant> getLatestProjectedTime();
}
