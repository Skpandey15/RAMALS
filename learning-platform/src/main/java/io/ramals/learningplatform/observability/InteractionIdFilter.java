package io.ramals.learningplatform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class InteractionIdFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(InteractionIdFilter.class);

  private final ObjectMapper objectMapper;
  private final TraceContextAccessor traceContext;

  public InteractionIdFilter(ObjectMapper objectMapper, TraceContextAccessor traceContext) {
    this.objectMapper = objectMapper;
    this.traceContext = traceContext;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long startedAt = System.nanoTime();
    String suppliedInteractionId = request.getHeader(CorrelationHeaders.INTERACTION_ID);
    boolean suppliedInteractionIdIsValid =
        suppliedInteractionId == null || UuidV7.isCanonical(suppliedInteractionId);
    String interactionId = suppliedInteractionId == null || !suppliedInteractionIdIsValid
        ? UuidV7.generate().toString()
        : suppliedInteractionId;
    String requestId = UUID.randomUUID().toString();

    request.setAttribute(CorrelationContext.INTERACTION_ID_ATTRIBUTE, interactionId);
    request.setAttribute(CorrelationContext.REQUEST_ID_ATTRIBUTE, requestId);
    response.setHeader(CorrelationHeaders.INTERACTION_ID, interactionId);
    response.setHeader(CorrelationHeaders.REQUEST_ID, requestId);

    try (MDC.MDCCloseable ignoredInteraction = MDC.putCloseable("interactionId", interactionId);
         MDC.MDCCloseable ignoredRequest = MDC.putCloseable("requestId", requestId);
         MDC.MDCCloseable ignoredMethod = MDC.putCloseable("http.method", request.getMethod())) {
      // This finally is nested *inside* the resource block on purpose. A try-with-resources closes
      // its resources before an outer finally runs, so summarising the request out there emitted
      // the one line per request that carried no interactionId -- precisely the line the diagnosis
      // procedure tells an engineer to search for. traceId survived only because it is removed
      // further down. The console pattern hid this until structured logging was switched on.
      try {
        putTraceContext();

        if (!suppliedInteractionIdIsValid) {
          writeInvalidInteractionId(response, interactionId);
          return;
        }

        filterChain.doFilter(request, response);
      } finally {
        putTraceResponseHeader(response);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        LOGGER.atInfo()
            .addKeyValue("operation", "http.request")
            .addKeyValue("statusCode", response.getStatus())
            .addKeyValue("durationMs", durationMs)
            .log("HTTP request completed");
        MDC.remove("traceId");
        MDC.remove("spanId");
      }
    }
  }

  private void putTraceContext() {
    String traceId = traceContext.traceId();
    String spanId = traceContext.spanId();
    if (!traceId.isEmpty()) {
      MDC.put("traceId", traceId);
    }
    if (!spanId.isEmpty()) {
      MDC.put("spanId", spanId);
    }
  }

  private void putTraceResponseHeader(HttpServletResponse response) {
    String traceId = traceContext.traceId();
    if (!traceId.isEmpty() && !response.isCommitted()) {
      response.setHeader(CorrelationHeaders.TRACE_ID, traceId);
    }
  }

  private void writeInvalidInteractionId(HttpServletResponse response, String interactionId) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    ApiProblem problem = new ApiProblem(
        "about:blank",
        "Invalid interaction identifier",
        400,
        "INVALID_INTERACTION_ID",
        "X-Interaction-ID must be a canonical lowercase UUIDv7.",
        interactionId,
        traceContext.traceId());
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
