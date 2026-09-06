package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

public final class KafkaEventPublisher implements EventPublisher {
  public static final String DEFAULT_TOPIC = "deployment-events.v1";
  private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);

  private final Producer<String, String> producer;
  private final String topic;

  public KafkaEventPublisher(Producer<String, String> producer, String topic) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.topic = Objects.requireNonNull(topic, "topic");
  }

  public static KafkaEventPublisher fromEnvironment(Map<String, String> env) {
    String bootstrapServers = env.get("KAFKA_BOOTSTRAP_SERVERS");
    if (bootstrapServers == null || bootstrapServers.isBlank()) {
      bootstrapServers = env.get("RELEASEGUARD_KAFKA_BOOTSTRAP_SERVERS");
    }
    if (bootstrapServers == null || bootstrapServers.isBlank()) {
      return null;
    }
    String topic = env.getOrDefault("RELEASEGUARD_KAFKA_TOPIC", DEFAULT_TOPIC);

    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    // RG-OUT-004: enable idempotence and acknowledgments from all in-sync replicas
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

    return new KafkaEventPublisher(new KafkaProducer<>(props), topic);
  }

  @Override
  public PublishResult publish(EventEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    String key = envelope.releaseUid();
    String value = Serialization.asJson(envelope);

    ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
    try {
      RecordMetadata metadata =
          producer.send(record).get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return new PublishResult(metadata.topic(), metadata.partition(), metadata.offset());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new EventPublishException(
          "Interrupted while publishing event " + envelope.eventId(), exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new EventPublishException(
          "Failed to publish event " + envelope.eventId() + " to Kafka", exception);
    }
  }

  @Override
  public void close() {
    producer.close();
  }
}
