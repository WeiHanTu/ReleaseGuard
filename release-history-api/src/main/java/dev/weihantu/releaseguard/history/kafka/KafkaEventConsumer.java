package dev.weihantu.releaseguard.history.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.history.config.HistoryProperties;
import dev.weihantu.releaseguard.history.projection.AuditProjectionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventConsumer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);

  private final AuditProjectionService projectionService;
  private final HistoryProperties properties;
  private final ObjectMapper objectMapper;
  private final Counter projectionFailures;
  private final Counter poisonRecords;
  private final AtomicBoolean running = new AtomicBoolean(false);

  private ExecutorService executor;
  private KafkaConsumer<String, String> consumer;

  public KafkaEventConsumer(
      AuditProjectionService projectionService,
      HistoryProperties properties,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.projectionFailures =
        Counter.builder("releaseguard.kafka.projection.failures")
            .description("Kafka records that could not be projected and were left uncommitted")
            .register(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    this.poisonRecords =
        Counter.builder("releaseguard.kafka.poison.records")
            .description("Malformed Kafka records durably quarantined before offset commit")
            .register(meterRegistry);
  }

  @Override
  public synchronized void start() {
    if (!properties.getKafka().isEnabled()) {
      log.info("Kafka consumer is disabled by configuration");
      return;
    }
    if (running.compareAndSet(false, true)) {
      log.info(
          "Starting Kafka consumer for topic {} on {}",
          properties.getKafka().getTopic(),
          properties.getKafka().getBootstrapServers());
      executor =
          Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r, "kafka-event-consumer");
                t.setDaemon(true);
                return t;
              });
      executor.submit(this::consumeLoop);
    }
  }

  @Override
  public synchronized void stop() {
    if (running.compareAndSet(true, false)) {
      log.info("Stopping Kafka event consumer...");
      if (consumer != null) {
        consumer.wakeup();
      }
      if (executor != null) {
        executor.shutdown();
        try {
          if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          executor.shutdownNow();
        }
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  public double projectionFailureCount() {
    return projectionFailures.count();
  }

  public double poisonRecordCount() {
    return poisonRecords.count();
  }

  private void consumeLoop() {
    Properties kafkaProps = new Properties();
    kafkaProps.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
    kafkaProps.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKafka().getGroupId());
    kafkaProps.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    kafkaProps.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    kafkaProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    kafkaProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    try {
      consumer = new KafkaConsumer<>(kafkaProps);
      consumer.subscribe(Collections.singletonList(properties.getKafka().getTopic()));

      while (running.get()) {
        try {
          ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
          processBatch(records);
        } catch (org.apache.kafka.common.errors.WakeupException e) {
          if (!running.get()) {
            break;
          }
          throw e;
        } catch (Exception e) {
          log.error("Error consuming Kafka records: {}", e.getMessage(), e);
        }
      }
    } catch (Exception e) {
      if (running.get()) {
        log.error("Kafka consumer terminated unexpectedly: {}", e.getMessage(), e);
      }
    } finally {
      if (consumer != null) {
        try {
          consumer.close(Duration.ofSeconds(2));
        } catch (Exception ignored) {
        }
      }
    }
  }

  public void processRecord(ConsumerRecord<String, String> record) {
    EventEnvelope envelope;
    try {
      envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      boolean inserted;
      try {
        inserted =
            projectionService.quarantineMalformedKafkaRecord(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value(),
                e);
      } catch (RuntimeException quarantineFailure) {
        projectionFailures.increment();
        log.error(
            "Failed to quarantine malformed Kafka record from topic {} partition {} offset {}: {}",
            record.topic(),
            record.partition(),
            record.offset(),
            quarantineFailure.getMessage());
        throw new EventProjectionException(
            record.topic(), record.partition(), record.offset(), quarantineFailure);
      }
      if (inserted) {
        poisonRecords.increment();
      }
      log.warn(
          "Quarantined malformed Kafka record from topic {} partition {} offset {}",
          record.topic(),
          record.partition(),
          record.offset());
      return;
    }

    try {
      projectionService.project(envelope, record.topic(), record.partition(), record.offset());
    } catch (RuntimeException e) {
      projectionFailures.increment();
      log.error(
          "Failed to project Kafka record from topic {} partition {} offset {}: {}",
          record.topic(),
          record.partition(),
          record.offset(),
          e.getMessage());
      throw new EventProjectionException(record.topic(), record.partition(), record.offset(), e);
    }
  }

  private void processBatch(ConsumerRecords<String, String> records) {
    if (records.isEmpty()) {
      return;
    }
    try {
      for (ConsumerRecord<String, String> record : records) {
        processRecord(record);
      }
      consumer.commitSync();
    } catch (RuntimeException exception) {
      for (TopicPartition partition : records.partitions()) {
        consumer.seek(partition, records.records(partition).getFirst().offset());
      }
      throw exception;
    }
  }

  static final class EventProjectionException extends RuntimeException {
    EventProjectionException(String topic, int partition, long offset, Throwable cause) {
      super("Failed to project Kafka record " + topic + "-" + partition + "@" + offset, cause);
    }
  }
}
