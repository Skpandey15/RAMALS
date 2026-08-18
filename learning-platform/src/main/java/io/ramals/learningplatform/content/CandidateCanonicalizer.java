package io.ramals.learningplatform.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.math.BigDecimal;
import java.math.BigInteger;
import tools.jackson.databind.json.JsonMapper;

/** Canonicalizes only the content a future reviewer will approve. */
public final class CandidateCanonicalizer {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private CandidateCanonicalizer() {
  }

  public static byte[] canonicalBytes(CandidateContent candidate) {
    Map<String, Object> document = new TreeMap<>();
    document.put("answerKey", List.copyOf(candidate.correctOptionIds()));
    document.put("assessmentVersionId", candidate.assessmentVersionId().toString());
    document.put("difficulty", candidate.difficulty());
    document.put("itemCode", candidate.itemCode());
    document.put("itemType", candidate.itemType());
    document.put("objectiveCode", candidate.objectiveCode());
    document.put("options", List.copyOf(candidate.options()));
    document.put("skillCode", candidate.skillCode());
    document.put("stem", candidate.stem());
    return canonicalBytes(document);
  }

  /** Canonicalizes an already allow-listed approval payload. */
  public static byte[] canonicalBytes(Map<String, Object> approvalPayload) {
    return MAPPER.writeValueAsString(normalize(approvalPayload)).getBytes(StandardCharsets.UTF_8);
  }

  /** Recursively sorts object keys while preserving array order and normalizing JSON numbers. */
  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> normalized = new TreeMap<>();
      map.forEach((key, nested) -> {
        if (!(key instanceof String stringKey)) {
          throw new IllegalArgumentException("Canonical JSON object keys must be strings");
        }
        normalized.put(stringKey, normalize(nested));
      });
      return normalized;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> normalized = new ArrayList<>(collection.size());
      collection.forEach(item -> normalized.add(normalize(item)));
      return normalized;
    }
    if (value instanceof Number number) {
      return normalizeNumber(number);
    }
    return value;
  }

  private static Number normalizeNumber(Number number) {
    if (number instanceof BigInteger || number instanceof Byte || number instanceof Short
        || number instanceof Integer || number instanceof Long) {
      return new BigInteger(number.toString());
    }
    if (number instanceof Float || number instanceof Double) {
      if (!Double.isFinite(number.doubleValue())) {
        throw new IllegalArgumentException("Canonical JSON does not support non-finite numbers");
      }
    }
    BigDecimal decimal = number instanceof BigDecimal
        ? (BigDecimal) number : BigDecimal.valueOf(number.doubleValue());
    decimal = decimal.stripTrailingZeros();
    return decimal.scale() < 0 ? decimal.setScale(0) : decimal;
  }

  public static String sha256(Map<String, Object> approvalPayload) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(approvalPayload));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(String.format("%02x", value));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
    }
  }

  public static String sha256(CandidateContent candidate) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(candidate));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(String.format("%02x", value));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
    }
  }

  /** Builds the JSONB payload persisted for the exact reviewed candidate revision. */
  public static Map<String, Object> payload(CandidateContent candidate) {
    Map<String, Object> payload = new TreeMap<>();
    payload.put("answerKey", new ArrayList<>(candidate.correctOptionIds()));
    payload.put("assessmentVersionId", candidate.assessmentVersionId().toString());
    payload.put("difficulty", candidate.difficulty());
    payload.put("itemCode", candidate.itemCode());
    payload.put("itemType", candidate.itemType());
    payload.put("objectiveCode", candidate.objectiveCode());
    payload.put("options", new ArrayList<>(candidate.options()));
    payload.put("skillCode", candidate.skillCode());
    payload.put("stem", candidate.stem());
    return payload;
  }
}
