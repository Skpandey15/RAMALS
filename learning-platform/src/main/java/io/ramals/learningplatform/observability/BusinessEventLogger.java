package io.ramals.learningplatform.observability;

import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Emits consequential business events using the structured SLF4J API.
 *
 * <p>Correlation identifiers are deliberately left in MDC: the HTTP filter and tracing bridge
 * own their lifecycle, and this keeps the same identifiers available to every event in a request.
 * Values supplied by domain code are bounded and sensitive-looking fields are redacted here as a
 * final safety net. This logger is intentionally stateless so it can be used by services without
 * changing their construction or transaction semantics.
 */
public final class BusinessEventLogger {

  private static final String SERVICE = "learning-platform";
  private static final String ENVIRONMENT = System.getProperty(
      "ramals.environment", System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "unknown"));
  private static final int MAX_VALUE_LENGTH = 256;
  private static final Set<String> MDC_OWNED_FIELDS =
      Set.of("interactionId", "traceId", "spanId");

  private BusinessEventLogger() {
  }

  public static void info(Logger logger, String operation, String message,
      Map<String, ?> fields) {
    write(logger.atInfo(), operation, message, fields);
  }

  public static void warn(Logger logger, String operation, String message,
      Map<String, ?> fields) {
    write(logger.atWarn(), operation, message, fields);
  }

  public static void error(Logger logger, String operation, String message,
      Throwable cause, Map<String, ?> fields) {
    var event = logger.atError().setCause(cause);
    write(event, operation, message, fields);
  }

  private static void write(org.slf4j.spi.LoggingEventBuilder event, String operation,
      String message, Map<String, ?> fields) {
    try (var ignored = CorrelationContext.withCorrelation(
        effectiveCorrelation(fields, "interactionId", MDC.get("interactionId")),
        effectiveCorrelation(fields, "traceId", MDC.get("traceId")))) {
      event.addKeyValue("service", SERVICE)
          .addKeyValue("environment", ENVIRONMENT)
          .addKeyValue("operation", safe(operation));
      if (fields != null) {
        fields.forEach((key, value) -> {
          if (key != null && !key.isBlank() && !MDC_OWNED_FIELDS.contains(key)) {
            event.addKeyValue(safe(key), safeField(key, value));
          }
        });
      }
      event.log(safe(message));
    }
  }

  private static String effectiveCorrelation(Map<String, ?> fields, String key, String current) {
    if (current != null && !current.isBlank()) {
      return current;
    }
    if (fields == null) {
      return null;
    }
    Object supplied = fields.get(key);
    return supplied == null ? null : String.valueOf(supplied);
  }

  /** Returns a bounded, non-sensitive representation suitable for structured logs. */
  public static Object safeField(String key, Object value) {
    if (value == null) {
      return null;
    }
    String normalized = key == null ? "" : key.toLowerCase();
    if (isSensitiveKey(normalized)) {
      return "[REDACTED]";
    }
    if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
      return value;
    }
    return safe(String.valueOf(value));
  }

  private static boolean isSensitiveKey(String key) {
    return key.contains("password") || key.contains("secret") || key.contains("authorization")
        || key.contains("api_key") || key.contains("apikey")
        || key.equals("token") || key.endsWith("token")
        || key.equals("prompt") || key.equals("raw_prompt")
        || key.equals("answer") || key.equals("raw_answer")
        || key.equals("content") || key.equals("raw_content") || key.equals("full_content")
        || key.equals("output") || key.equals("raw_output") || key.equals("llm_output");
  }

  private static String safe(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= MAX_VALUE_LENGTH
        ? value
        : value.substring(0, MAX_VALUE_LENGTH) + "…";
  }
}
