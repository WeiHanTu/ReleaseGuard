package dev.weihantu.releaseguard.history.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRepository implements AuditRepository {

  private static final EventRowMapper EVENT_ROW_MAPPER = new EventRowMapper();
  private static final ReleaseRowMapper RELEASE_ROW_MAPPER = new ReleaseRowMapper();

  private final JdbcTemplate jdbcTemplate;

  public JdbcAuditRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public boolean eventExists(String eventId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM events WHERE event_id = ?", Integer.class, eventId);
    return count != null && count > 0;
  }

  @Override
  public void insertEvent(EventRecord event) {
    jdbcTemplate.update(
        """
        INSERT INTO events (
            event_id, release_id, release_uid, generation, sequence, type,
            occurred_at, namespace, release_name, git_revision, image_digest,
            trace_id, payload, kafka_topic, kafka_partition, kafka_offset, ingested_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.eventId(),
        event.releaseId(),
        event.releaseUid(),
        event.generation(),
        event.sequence(),
        event.type(),
        Timestamp.from(event.occurredAt()),
        event.namespace(),
        event.releaseName(),
        event.gitRevision(),
        event.imageDigest(),
        event.traceId(),
        event.payload(),
        event.kafkaTopic(),
        event.kafkaPartition(),
        event.kafkaOffset(),
        Timestamp.from(event.ingestedAt()));
  }

  @Override
  public Optional<ReleaseProjectionRecord> findReleaseById(String releaseId) {
    List<ReleaseProjectionRecord> results =
        jdbcTemplate.query(
            """
            SELECT release_id, release_uid, namespace, name, generation, phase,
                   current_step, stable_revision, canary_revision, last_transition_sequence,
                   last_event_id, last_event_type, last_event_at, gate_decision,
                   gate_evidence_ref, gate_evidence_digest, updated_at
            FROM releases WHERE release_id = ?
            """,
            RELEASE_ROW_MAPPER,
            releaseId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
  }

  @Override
  public Optional<ReleaseProjectionRecord> findLatestRelease(String namespace, String name) {
    List<ReleaseProjectionRecord> results =
        jdbcTemplate.query(
            """
            SELECT release_id, release_uid, namespace, name, generation, phase,
                   current_step, stable_revision, canary_revision, last_transition_sequence,
                   last_event_id, last_event_type, last_event_at, gate_decision,
                   gate_evidence_ref, gate_evidence_digest, updated_at
            FROM releases
            WHERE namespace = ? AND name = ?
            ORDER BY generation DESC, last_transition_sequence DESC
            LIMIT 1
            """,
            RELEASE_ROW_MAPPER,
            namespace,
            name);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
  }

  @Override
  public void upsertRelease(ReleaseProjectionRecord record) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE releases SET
                release_uid = ?, namespace = ?, name = ?, generation = ?,
                phase = ?, current_step = ?, stable_revision = ?, canary_revision = ?,
                last_transition_sequence = ?, last_event_id = ?, last_event_type = ?,
                last_event_at = ?, gate_decision = ?, gate_evidence_ref = ?,
                gate_evidence_digest = ?, updated_at = ?
            WHERE release_id = ?
            """,
            record.releaseUid(),
            record.namespace(),
            record.name(),
            record.generation(),
            record.phase(),
            record.currentStep(),
            record.stableRevision(),
            record.canaryRevision(),
            record.lastTransitionSequence(),
            record.lastEventId(),
            record.lastEventType(),
            Timestamp.from(record.lastEventAt()),
            record.gateDecision(),
            record.gateEvidenceRef(),
            record.gateEvidenceDigest(),
            Timestamp.from(record.updatedAt()),
            record.releaseId());

    if (updated == 0) {
      jdbcTemplate.update(
          """
          INSERT INTO releases (
              release_id, release_uid, namespace, name, generation, phase,
              current_step, stable_revision, canary_revision, last_transition_sequence,
              last_event_id, last_event_type, last_event_at, gate_decision,
              gate_evidence_ref, gate_evidence_digest, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          record.releaseId(),
          record.releaseUid(),
          record.namespace(),
          record.name(),
          record.generation(),
          record.phase(),
          record.currentStep(),
          record.stableRevision(),
          record.canaryRevision(),
          record.lastTransitionSequence(),
          record.lastEventId(),
          record.lastEventType(),
          Timestamp.from(record.lastEventAt()),
          record.gateDecision(),
          record.gateEvidenceRef(),
          record.gateEvidenceDigest(),
          Timestamp.from(record.updatedAt()));
    }
  }

  @Override
  public void deleteReleaseProjections() {
    jdbcTemplate.update("DELETE FROM releases");
  }

  @Override
  public List<EventRecord> findEventsByRelease(
      String namespace, String name, Long afterSequence, int limit) {
    if (afterSequence == null) {
      return jdbcTemplate.query(
          """
          SELECT event_id, release_id, release_uid, generation, sequence, type,
                 occurred_at, namespace, release_name, git_revision, image_digest,
                 trace_id, payload, kafka_topic, kafka_partition, kafka_offset, ingested_at
          FROM events
          WHERE namespace = ? AND release_name = ?
          ORDER BY sequence ASC
          LIMIT ?
          """,
          EVENT_ROW_MAPPER,
          namespace,
          name,
          limit);
    }
    return jdbcTemplate.query(
        """
        SELECT event_id, release_id, release_uid, generation, sequence, type,
               occurred_at, namespace, release_name, git_revision, image_digest,
               trace_id, payload, kafka_topic, kafka_partition, kafka_offset, ingested_at
        FROM events
        WHERE namespace = ? AND release_name = ? AND sequence > ?
        ORDER BY sequence ASC
        LIMIT ?
        """,
        EVENT_ROW_MAPPER,
        namespace,
        name,
        afterSequence,
        limit);
  }

  @Override
  public List<EventRecord> findEventsByReleaseId(String releaseId, Long afterSequence, int limit) {
    if (afterSequence == null) {
      return jdbcTemplate.query(
          """
          SELECT event_id, release_id, release_uid, generation, sequence, type,
                 occurred_at, namespace, release_name, git_revision, image_digest,
                 trace_id, payload, kafka_topic, kafka_partition, kafka_offset, ingested_at
          FROM events
          WHERE release_id = ?
          ORDER BY sequence ASC
          LIMIT ?
          """,
          EVENT_ROW_MAPPER,
          releaseId,
          limit);
    }
    return jdbcTemplate.query(
        """
        SELECT event_id, release_id, release_uid, generation, sequence, type,
               occurred_at, namespace, release_name, git_revision, image_digest,
               trace_id, payload, kafka_topic, kafka_partition, kafka_offset, ingested_at
        FROM events
        WHERE release_id = ? AND sequence > ?
        ORDER BY sequence ASC
        LIMIT ?
        """,
        EVENT_ROW_MAPPER,
        releaseId,
        afterSequence,
        limit);
  }

  @Override
  public List<EventRecord> findAllEventsOrdered() {
    return jdbcTemplate.query(
        """
        SELECT event_id, release_id, release_uid, generation, sequence, type,
               occurred_at, namespace, release_name, git_revision, image_digest,
               trace_id, payload, kafka_topic, kafka_partition, kafka_offset, ingested_at
        FROM events
        ORDER BY release_id ASC, sequence ASC
        """,
        EVENT_ROW_MAPPER);
  }

  @Override
  public void insertQuarantinedEvent(QuarantinedEventRecord quarantined) {
    jdbcTemplate.update(
        """
        INSERT INTO quarantined_events (
            event_id, release_id, sequence, expected_sequence, reason, raw_envelope, quarantined_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        quarantined.eventId(),
        quarantined.releaseId(),
        quarantined.sequence(),
        quarantined.expectedSequence(),
        quarantined.reason(),
        quarantined.rawEnvelope(),
        Timestamp.from(quarantined.quarantinedAt()));
  }

  @Override
  public long countQuarantinedEvents(String releaseId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM quarantined_events WHERE release_id = ?", Long.class, releaseId);
    return count == null ? 0L : count;
  }

  @Override
  public long countAllQuarantinedEvents() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quarantined_events", Long.class);
    return count == null ? 0L : count;
  }

  @Override
  public void upsertCheckpoint(String topic, int partitionId, long lastOffset, Instant updatedAt) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE projection_checkpoints
            SET last_offset = ?, updated_at = ?
            WHERE topic = ? AND partition_id = ?
            """,
            lastOffset,
            Timestamp.from(updatedAt),
            topic,
            partitionId);

    if (updated == 0) {
      jdbcTemplate.update(
          """
          INSERT INTO projection_checkpoints (topic, partition_id, last_offset, updated_at)
          VALUES (?, ?, ?, ?)
          """,
          topic,
          partitionId,
          lastOffset,
          Timestamp.from(updatedAt));
    }
  }

  @Override
  public Map<Integer, Long> getCheckpoints(String topic) {
    Map<Integer, Long> checkpoints = new HashMap<>();
    jdbcTemplate.query(
        "SELECT partition_id, last_offset FROM projection_checkpoints WHERE topic = ?",
        rs -> {
          checkpoints.put(rs.getInt("partition_id"), rs.getLong("last_offset"));
        },
        topic);
    return Map.copyOf(checkpoints);
  }

  @Override
  public Optional<Instant> getLatestProjectedTime() {
    List<Timestamp> timestamps =
        jdbcTemplate.query(
            "SELECT MAX(occurred_at) AS latest_time FROM events",
            (rs, rowNum) -> rs.getTimestamp("latest_time"));
    if (timestamps.isEmpty() || timestamps.getFirst() == null) {
      return Optional.empty();
    }
    return Optional.of(timestamps.getFirst().toInstant());
  }

  private static final class EventRowMapper implements RowMapper<EventRecord> {
    @Override
    public EventRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
      Timestamp occurredTs = rs.getTimestamp("occurred_at");
      Timestamp ingestedTs = rs.getTimestamp("ingested_at");
      int partitionVal = rs.getInt("kafka_partition");
      Integer partition = rs.wasNull() ? null : partitionVal;
      long offsetVal = rs.getLong("kafka_offset");
      Long offset = rs.wasNull() ? null : offsetVal;

      return new EventRecord(
          rs.getString("event_id"),
          rs.getString("release_id"),
          rs.getString("release_uid"),
          rs.getLong("generation"),
          rs.getLong("sequence"),
          rs.getString("type"),
          occurredTs != null ? occurredTs.toInstant() : Instant.EPOCH,
          rs.getString("namespace"),
          rs.getString("release_name"),
          rs.getString("git_revision"),
          rs.getString("image_digest"),
          rs.getString("trace_id"),
          rs.getString("payload"),
          rs.getString("kafka_topic"),
          partition,
          offset,
          ingestedTs != null ? ingestedTs.toInstant() : Instant.EPOCH);
    }
  }

  private static final class ReleaseRowMapper implements RowMapper<ReleaseProjectionRecord> {
    @Override
    public ReleaseProjectionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
      Timestamp lastEventTs = rs.getTimestamp("last_event_at");
      Timestamp updatedTs = rs.getTimestamp("updated_at");
      int stepVal = rs.getInt("current_step");
      Integer currentStep = rs.wasNull() ? null : stepVal;

      return new ReleaseProjectionRecord(
          rs.getString("release_id"),
          rs.getString("release_uid"),
          rs.getString("namespace"),
          rs.getString("name"),
          rs.getLong("generation"),
          rs.getString("phase"),
          currentStep,
          rs.getString("stable_revision"),
          rs.getString("canary_revision"),
          rs.getLong("last_transition_sequence"),
          rs.getString("last_event_id"),
          rs.getString("last_event_type"),
          lastEventTs != null ? lastEventTs.toInstant() : Instant.EPOCH,
          rs.getString("gate_decision"),
          rs.getString("gate_evidence_ref"),
          rs.getString("gate_evidence_digest"),
          updatedTs != null ? updatedTs.toInstant() : Instant.EPOCH);
    }
  }
}
