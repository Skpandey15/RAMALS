package io.ramals.learningplatform.observability;

import jakarta.servlet.http.HttpServletRequest;
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

