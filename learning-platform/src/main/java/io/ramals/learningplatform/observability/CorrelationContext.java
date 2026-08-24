package io.ramals.learningplatform.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;

public final class CorrelationContext {

  public static final String INTERACTION_ID_ATTRIBUTE = CorrelationContext.class.getName() + ".interactionId";
  public static final String REQUEST_ID_ATTRIBUTE = CorrelationContext.class.getName() + ".requestId";

  private CorrelationContext() {
  }

  /** The interactionId bound to the current request thread (via MDC), or empty if none. */
  public static String currentInteractionId() {
    String value = MDC.get("interactionId");
    return value == null ? "" : value;
  }

  /** The W3C traceId bound to the current request thread (via MDC), or empty if untraced. */
  public static String currentTraceId() {
    String value = MDC.get("traceId");
    return value == null ? "" : value;
  }

  /**
   * Binds persisted workflow correlation to the current worker thread for one log or operation.
   *
   * <p>Structured logging serializes MDC and fluent key/value pairs into the same JSON object. The
   * former is the canonical owner of these correlation fields, so callers that need to re-establish
   * a durable correlation after an asynchronous hand-off use this scope and do not add the fields
   * again as structured arguments. The complete prior MDC is restored on close, including any
   * request context that surrounded the scope.
   */
  public static Scope withCorrelation(String interactionId, String traceId) {
    return new Scope(interactionId, traceId);
  }

  public static final class Scope implements AutoCloseable {

    private final Map<String, String> previous;
    private boolean closed;

    private Scope(String interactionId, String traceId) {
      this.previous = MDC.getCopyOfContextMap();
      putOrRemove("interactionId", interactionId);
      putOrRemove("traceId", traceId);

      // A persisted traceId and a spanId from a different current trace are not a valid pair. A
      // worker normally has no span, but this also makes a hand-off safe when it occurs inside an
      // HTTP span whose trace differs from the durable workflow's original request.
      if (!Objects.equals(normalize(traceId), normalize(previousValue("traceId")))) {
        MDC.remove("spanId");
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      MDC.clear();
      if (previous != null) {
        MDC.setContextMap(previous);
      }
    }

    private String previousValue(String key) {
      return previous == null ? null : previous.get(key);
    }
  }

  private static void putOrRemove(String key, String value) {
    if (value == null || value.isBlank()) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  public static String interactionId(HttpServletRequest request) {
    return stringAttribute(request, INTERACTION_ID_ATTRIBUTE);
  }

  public static String requestId(HttpServletRequest request) {
    return stringAttribute(request, REQUEST_ID_ATTRIBUTE);
  }

  private static String stringAttribute(HttpServletRequest request, String name) {
    Object value = request.getAttribute(name);
    return value instanceof String string ? string : "";
  }
}
