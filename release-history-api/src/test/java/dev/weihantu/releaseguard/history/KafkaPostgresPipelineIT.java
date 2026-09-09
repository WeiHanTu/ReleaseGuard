package dev.weihantu.releaseguard.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.history.kafka.KafkaEventConsumer;
import dev.weihantu.releaseguard.history.repository.AuditRepository;
import dev.weihantu.releaseguard.history.repository.ReleaseProjectionRecord;
import dev.weihantu.releaseguard.operator.KafkaEventPublisher;
import dev.weihantu.releaseguard.operator.ReleaseEventRenderer;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class KafkaPostgresPipelineIT {
  private static final String TOPIC = "deployment-events.v1";
  private static final String GROUP_ID = "releaseguard-real-integration-test";

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine");

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

  @DynamicPropertySource
  static void configureDependencies(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("releaseguard.history.kafka.enabled", () -> "true");
    registry.add("releaseguard.history.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("releaseguard.history.kafka.topic", () -> TOPIC);
    registry.add("releaseguard.history.kafka.group-id", () -> GROUP_ID);
  }

  @Autowired private AuditRepository auditRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KafkaEventConsumer kafkaEventConsumer;

  @Test
  void operatorEventTraversesRealKafkaIntoRealPostgres() {
    String databaseVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
    assertTrue(databaseVersion != null && databaseVersion.startsWith("PostgreSQL"));

    ReleasePlan plan = plan();
    ReleasePlanStatus.Transition transition =
        new ReleasePlanStatus.Transition(
            1L, "evt-real-pipeline-1", "release.bootstrapped", Instant.now().toString());
    ReleasePlanStatus status =
        new ReleasePlanStatus(
            1L,
            "uid-real-pipeline:1",
            ReleasePlanStatus.Phase.Succeeded,
            2,
            "checkout-candidate-hash",
            null,
            null,
            new ReleasePlanStatus.GateDecision(
                "Successful", "AnalysisRun/checkout-bootstrap-g1", Instant.now().toString()),
            transition,
            0,
            List.of());
    ReleaseEvent outboxEvent = ReleaseEventRenderer.render(plan, transition, status);
    EventEnvelope envelope = EventEnvelope.from(outboxEvent, plan);

    try (KafkaEventPublisher publisher =
        KafkaEventPublisher.fromEnvironment(
            Map.of(
                "KAFKA_BOOTSTRAP_SERVERS",
                KAFKA.getBootstrapServers(),
                "RELEASEGUARD_KAFKA_TOPIC",
                TOPIC))) {
      publisher.publish(envelope);
    }

    ReleaseProjectionRecord projected =
        awaitProjection(envelope.releaseId(), Duration.ofSeconds(20));
    assertEquals("Succeeded", projected.phase());
    assertEquals("Successful", projected.gateDecision());
    assertEquals("AnalysisRun/checkout-bootstrap-g1", projected.gateEvidenceRef());
    assertTrue(auditRepository.eventExists(envelope.eventId()));
    assertTrue(
        auditRepository.getCheckpoints(TOPIC).values().stream().anyMatch(offset -> offset >= 0L));

    long committedAfterSuccess = awaitCommittedOffset(1L, Duration.ofSeconds(10));
    publishMalformedRecord();
    awaitPoisonQuarantine(Duration.ofSeconds(10));
    assertEquals(1L, poisonRecordCount());
    assertTrue(kafkaEventConsumer.poisonRecordCount() >= 1.0);
    long committedAfterPoison =
        awaitCommittedOffset(committedAfterSuccess + 1L, Duration.ofSeconds(10));

    EventEnvelope followingEvent =
        new EventEnvelope(
            1,
            "evt-real-pipeline-2",
            "release.progressing",
            envelope.releaseId(),
            envelope.releaseUid(),
            envelope.generation(),
            2L,
            envelope.namespace(),
            envelope.releaseName(),
            "def456",
            "sha256:following-image",
            Instant.now().toString(),
            "trace-after-poison",
            Map.of("phase", "Progressing", "currentStep", 3));
    publish(followingEvent);

    ReleaseProjectionRecord recovered =
        awaitProjectionSequence(followingEvent.releaseId(), 2L, Duration.ofSeconds(20));
    assertEquals("Progressing", recovered.phase());
    assertEquals(3, recovered.currentStep());
    long committedAfterRecovery =
        awaitCommittedOffset(committedAfterPoison + 1L, Duration.ofSeconds(10));
    assertTrue(
        committedAfterRecovery > committedAfterPoison,
        "a quarantined poison record must not block later records in the partition");

    double failuresBeforeDatabaseOutage = kafkaEventConsumer.projectionFailureCount();
    jdbcTemplate.execute("DROP TABLE releases");
    EventEnvelope databaseFailureEvent =
        new EventEnvelope(
            1,
            "evt-real-pipeline-3",
            "release.progressing",
            envelope.releaseId(),
            envelope.releaseUid(),
            envelope.generation(),
            3L,
            envelope.namespace(),
            envelope.releaseName(),
            "ghi789",
            "sha256:database-failure-image",
            Instant.now().toString(),
            "trace-database-failure",
            Map.of("phase", "Progressing", "currentStep", 4));
    publish(databaseFailureEvent);
    awaitProjectionFailure(failuresBeforeDatabaseOutage, Duration.ofSeconds(10));
    assertEquals(
        committedAfterRecovery,
        committedOffset(),
        "a transient PostgreSQL failure must leave the Kafka record uncommitted for retry");
  }

  private ReleaseProjectionRecord awaitProjection(String releaseId, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      var projection = auditRepository.findReleaseById(releaseId);
      if (projection.isPresent()) {
        return projection.get();
      }
      LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
    }
    throw new AssertionError("Timed out waiting for Kafka event projection: " + releaseId);
  }

  private ReleaseProjectionRecord awaitProjectionSequence(
      String releaseId, long sequence, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      var projection = auditRepository.findReleaseById(releaseId);
      if (projection.isPresent() && projection.get().lastTransitionSequence() >= sequence) {
        return projection.get();
      }
      LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
    }
    throw new AssertionError(
        "Timed out waiting for Kafka projection sequence " + sequence + ": " + releaseId);
  }

  private long awaitCommittedOffset(long minimum, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      long offset = committedOffset();
      if (offset >= minimum) {
        return offset;
      }
      LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
    }
    throw new AssertionError("Timed out waiting for committed Kafka offset " + minimum);
  }

  private long committedOffset() {
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      Map<TopicPartition, OffsetAndMetadata> offsets =
          admin.listConsumerGroupOffsets(GROUP_ID).partitionsToOffsetAndMetadata().get();
      return offsets.values().stream().mapToLong(OffsetAndMetadata::offset).max().orElse(-1L);
    } catch (Exception exception) {
      throw new AssertionError("Could not read committed Kafka offsets", exception);
    }
  }

  private void publishMalformedRecord() {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
      producer.send(new ProducerRecord<>(TOPIC, "uid-real-pipeline", "{not-json")).get();
    } catch (Exception exception) {
      throw new AssertionError("Could not publish malformed Kafka record", exception);
    }
  }

  private void publish(EventEnvelope envelope) {
    try (KafkaEventPublisher publisher =
        KafkaEventPublisher.fromEnvironment(
            Map.of(
                "KAFKA_BOOTSTRAP_SERVERS",
                KAFKA.getBootstrapServers(),
                "RELEASEGUARD_KAFKA_TOPIC",
                TOPIC))) {
      publisher.publish(envelope);
    }
  }

  private void awaitPoisonQuarantine(Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (poisonRecordCount() >= 1L) {
        return;
      }
      LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
    }
    throw new AssertionError("Timed out waiting for malformed Kafka record quarantine");
  }

  private long poisonRecordCount() {
    Long count =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kafka_poison_records", Long.class);
    return count == null ? 0L : count;
  }

  private void awaitProjectionFailure(double baseline, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (kafkaEventConsumer.projectionFailureCount() > baseline) {
        return;
      }
      LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
    }
    throw new AssertionError("Timed out waiting for retriable PostgreSQL projection failure");
  }

  private static ReleasePlan plan() {
    ReleasePlan plan = new ReleasePlan();
    plan.setMetadata(
        new ObjectMetaBuilder()
            .withName("checkout")
            .withNamespace("demo")
            .withUid("uid-real-pipeline")
            .withGeneration(1L)
            .addToAnnotations("delivery.weihantu.dev/git-revision", "abc123")
            .build());
    return plan;
  }
}
