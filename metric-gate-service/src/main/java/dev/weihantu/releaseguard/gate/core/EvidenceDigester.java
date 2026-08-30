package dev.weihantu.releaseguard.gate.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class EvidenceDigester {
  private final ObjectMapper objectMapper;

  public EvidenceDigester(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String digest(Object evidence) {
    try {
      byte[] canonical = objectMapper.writeValueAsBytes(canonical(evidence));
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("cannot canonicalize gate evidence", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private Object canonical(Object value) {
    Object converted =
        value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Map<?, ?>
                || value instanceof Iterable<?>
            ? value
            : objectMapper.convertValue(value, Object.class);
    if (converted instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, child) -> sorted.put(key.toString(), canonical(child)));
      return sorted;
    }
    if (converted instanceof Iterable<?> iterable) {
      List<Object> sorted = new ArrayList<>();
      iterable.forEach(child -> sorted.add(canonical(child)));
      return List.copyOf(sorted);
    }
    return converted;
  }
}
