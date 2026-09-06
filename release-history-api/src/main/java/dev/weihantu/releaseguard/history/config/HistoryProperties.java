package dev.weihantu.releaseguard.history.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "releaseguard.history")
public class HistoryProperties {

  private Kafka kafka = new Kafka();
  private long staleThresholdSeconds = 300;

  public Kafka getKafka() {
    return kafka;
  }

  public void setKafka(Kafka kafka) {
    this.kafka = kafka;
  }

  public long getStaleThresholdSeconds() {
    return staleThresholdSeconds;
  }

  public void setStaleThresholdSeconds(long staleThresholdSeconds) {
    this.staleThresholdSeconds = staleThresholdSeconds;
  }

  public static class Kafka {
    private boolean enabled = false;
    private String bootstrapServers = "localhost:9092";
    private String topic = "deployment-events.v1";
    private String groupId = "releaseguard-history-projection";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getBootstrapServers() {
      return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
      this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
      return topic;
    }

    public void setTopic(String topic) {
      this.topic = topic;
    }

    public String getGroupId() {
      return groupId;
    }

    public void setGroupId(String groupId) {
      this.groupId = groupId;
    }
  }
}
