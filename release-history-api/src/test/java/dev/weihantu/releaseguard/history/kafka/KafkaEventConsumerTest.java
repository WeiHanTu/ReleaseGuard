package dev.weihantu.releaseguard.history.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.history.config.HistoryProperties;
import dev.weihantu.releaseguard.history.projection.AuditProjectionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class KafkaEventConsumerTest {

  private AuditProjectionService projectionService;
  private KafkaEventConsumer consumer;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    projectionService = mock(AuditProjectionService.class);
    objectMapper = new ObjectMapper();
    consumer =
        new KafkaEventConsumer(
            projectionService, new HistoryProperties(), objectMapper, new SimpleMeterRegistry());
  }

  @Test
  void quarantineDatabaseFailureRemainsRetriable() {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("deployment-events.v1", 0, 7L, "uid-1", "{not-json");
    when(projectionService.quarantineMalformedKafkaRecord(
            eq("deployment-events.v1"),
            eq(0),
            eq(7L),
            eq("uid-1"),
            eq("{not-json"),
            any(Exception.class)))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThrows(
        KafkaEventConsumer.EventProjectionException.class, () -> consumer.processRecord(record));
    assertEquals(1.0, consumer.projectionFailureCount());
    assertEquals(0.0, consumer.poisonRecordCount());
    verify(projectionService, never()).project(any(), any(), any(), any());
  }

  @Test
  void validEnvelopeDatabaseFailureIsNotQuarantined() throws Exception {
    EventEnvelope envelope =
        new EventEnvelope(
            1,
            "evt-1",
            "release.progressing",
            "uid-1:1",
            "uid-1",
            1L,
            1L,
            "demo",
            "checkout",
            "abc123",
            "sha256:image",
            "2026-09-09T00:00:00Z",
            "trace-1",
            Map.of("phase", "Progressing"));
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(
            "deployment-events.v1", 0, 8L, "uid-1", objectMapper.writeValueAsString(envelope));
    when(projectionService.project(any(), eq("deployment-events.v1"), eq(0), eq(8L)))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThrows(
        KafkaEventConsumer.EventProjectionException.class, () -> consumer.processRecord(record));
    assertEquals(1.0, consumer.projectionFailureCount());
    verify(projectionService, never())
        .quarantineMalformedKafkaRecord(
            any(), any(Integer.class), any(Long.class), any(), any(), any());
  }
}
