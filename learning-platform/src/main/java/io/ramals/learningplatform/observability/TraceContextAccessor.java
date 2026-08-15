package io.ramals.learningplatform.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

@Component
public class TraceContextAccessor {

  private final Tracer tracer;

  public TraceContextAccessor(Tracer tracer) {
    this.tracer = tracer;
  }

  public String traceId() {
    Span span = tracer.currentSpan();
    return span == null ? "" : span.context().traceId();
  }

  public String spanId() {
    Span span = tracer.currentSpan();
    return span == null ? "" : span.context().spanId();
  }
}

