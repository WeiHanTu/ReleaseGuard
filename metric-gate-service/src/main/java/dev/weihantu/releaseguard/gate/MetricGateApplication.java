package dev.weihantu.releaseguard.gate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MetricGateApplication {
  public static void main(String[] args) {
    SpringApplication.run(MetricGateApplication.class, args);
  }
}
