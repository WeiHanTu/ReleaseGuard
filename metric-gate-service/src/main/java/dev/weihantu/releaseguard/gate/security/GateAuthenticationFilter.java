package dev.weihantu.releaseguard.gate.security;

import dev.weihantu.releaseguard.gate.config.GateProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class GateAuthenticationFilter extends OncePerRequestFilter {
  private static final String EVALUATION_PATH = "/internal/v1/evaluations";
  private static final String BEARER_PREFIX = "Bearer ";

  private final byte[] expectedToken;

  public GateAuthenticationFilter(GateProperties properties) {
    expectedToken = properties.authToken().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !(request.getContextPath() + EVALUATION_PATH).equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (isAuthorized(authorization)) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write("{\"code\":\"Unauthorized\"}");
  }

  private boolean isAuthorized(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      return false;
    }
    byte[] suppliedToken =
        authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedToken, suppliedToken);
  }
}
