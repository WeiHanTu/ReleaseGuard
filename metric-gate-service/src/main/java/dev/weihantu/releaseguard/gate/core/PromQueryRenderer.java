package dev.weihantu.releaseguard.gate.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PromQueryRenderer {
  private static final Pattern SUBSTITUTION =
      Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*}}", Pattern.MULTILINE);
  private static final int MAXIMUM_QUERY_LENGTH = 16_384;

  public String render(String template, Map<String, String> substitutions) {
    Matcher matcher = SUBSTITUTION.matcher(template);
    StringBuilder rendered = new StringBuilder();
    while (matcher.find()) {
      String name = matcher.group(1);
      String value = substitutions.get(name);
      if (value == null) {
        throw new IllegalArgumentException("missing query substitution: " + name);
      }
      boolean quoted = insideQuotedString(template, matcher.start());
      if ("window".equals(name) && quoted) {
        throw new IllegalArgumentException("window substitution must not be quoted");
      }
      if (!"window".equals(name) && !quoted) {
        throw new IllegalArgumentException(name + " substitution must be inside a string literal");
      }
      String replacement = "window".equals(name) ? value : escapeStringLiteral(value);
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(rendered);
    if (rendered.indexOf("{{") >= 0 || rendered.indexOf("}}") >= 0) {
      throw new IllegalArgumentException("query contains an unresolved substitution");
    }
    if (rendered.length() > MAXIMUM_QUERY_LENGTH) {
      throw new IllegalArgumentException("rendered query exceeds maximum length");
    }
    return rendered.toString();
  }

  private static boolean insideQuotedString(String value, int endExclusive) {
    boolean quoted = false;
    for (int index = 0; index < endExclusive; index++) {
      if (value.charAt(index) == '"' && !escaped(value, index)) {
        quoted = !quoted;
      }
    }
    return quoted;
  }

  private static boolean escaped(String value, int index) {
    int backslashes = 0;
    for (int cursor = index - 1; cursor >= 0 && value.charAt(cursor) == '\\'; cursor--) {
      backslashes++;
    }
    return backslashes % 2 == 1;
  }

  private static String escapeStringLiteral(String value) {
    StringBuilder escaped = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (Character.isISOControl(character)) {
            escaped.append("\\x").append(String.format("%02x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }
}
