package dev.weihantu.releaseguard.api.v1alpha1;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;

public final class KubernetesDuration {
  public static final String PATTERN = "^[1-9][0-9]*(ms|s|m|h)$";
  private static final java.util.regex.Pattern DURATION = java.util.regex.Pattern.compile(PATTERN);

  private KubernetesDuration() {}

  public static Optional<Duration> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    Matcher matcher = DURATION.matcher(value);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      long amount = Long.parseLong(value.substring(0, value.length() - matcher.group(1).length()));
      return switch (matcher.group(1)) {
        case "ms" -> Optional.of(Duration.ofMillis(amount));
        case "s" -> Optional.of(Duration.ofSeconds(amount));
        case "m" -> Optional.of(Duration.ofMinutes(amount));
        case "h" -> Optional.of(Duration.ofHours(amount));
        default -> Optional.empty();
      };
    } catch (ArithmeticException | NumberFormatException exception) {
      return Optional.empty();
    }
  }
}
