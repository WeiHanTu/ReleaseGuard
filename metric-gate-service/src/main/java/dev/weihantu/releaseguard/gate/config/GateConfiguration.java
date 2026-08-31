package dev.weihantu.releaseguard.gate.config;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GateConfiguration {
  @Bean
  HttpClient httpClient() {
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Bean
  Clock systemClock() {
    return Clock.systemUTC();
  }
}
