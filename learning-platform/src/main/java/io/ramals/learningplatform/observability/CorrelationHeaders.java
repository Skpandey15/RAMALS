package io.ramals.learningplatform.observability;

public final class CorrelationHeaders {

  public static final String INTERACTION_ID = "X-Interaction-ID";
  public static final String REQUEST_ID = "X-Request-ID";
  public static final String TRACE_ID = "X-Trace-ID";

  private CorrelationHeaders() {
  }
}

