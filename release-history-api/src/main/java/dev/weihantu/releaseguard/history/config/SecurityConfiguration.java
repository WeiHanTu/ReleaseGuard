package dev.weihantu.releaseguard.history.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.history.api.ApiError;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ObjectMapper objectMapper, JwtDecoder jwtDecoder) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.POST, "/v1/releases/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .jwt(jwt -> jwt.decoder(jwtDecoder))
                    .authenticationEntryPoint(
                        (request, response, exception) -> {
                          response.setStatus(401);
                          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                          objectMapper.writeValue(
                              response.getOutputStream(),
                              new ApiError(
                                  "AuthenticationRequired",
                                  "A valid bearer JWT is required for mutating requests"));
                        }));
    return http.build();
  }

  @Bean
  @ConditionalOnMissingBean
  JwtDecoder jwtDecoder(HistoryProperties properties) {
    HistoryProperties.Auth auth = Objects.requireNonNull(properties.getAuth(), "auth");
    String issuerUri = normalized(auth.getIssuerUri());
    String jwkSetUri = normalized(auth.getJwkSetUri());

    if (jwkSetUri != null) {
      NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
      OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefault();
      if (issuerUri != null) {
        validator = JwtValidators.createDefaultWithIssuer(issuerUri);
      }
      decoder.setJwtValidator(validator);
      return decoder;
    }
    if (issuerUri != null) {
      return JwtDecoders.fromIssuerLocation(issuerUri);
    }
    return token -> {
      throw new JwtException("JWT verification is not configured");
    };
  }

  private static String normalized(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
