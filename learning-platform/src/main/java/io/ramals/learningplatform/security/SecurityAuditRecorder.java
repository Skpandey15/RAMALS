package io.ramals.learningplatform.security;

import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.TraceContextAccessor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Records authentication and authorization decisions in {@code audit.security_audit}
 * (Master Plan §8).
 *
 * <p>Denials arrive by two different routes and both must be audited. A filter-chain denial —
 * missing or invalid token — is handled by {@link SecurityDenialHandler}. A method-security denial
 * from {@code @PreAuthorize} is thrown inside the controller and handled by the
 * {@code @RestControllerAdvice}, so it never reaches the filter chain's {@code AccessDeniedHandler}.
 * Auditing in only one of those places silently misses every role-based denial, which is the more
 * interesting half for a security investigation.
 */
@Component
public class SecurityAuditRecorder {

  public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
  public static final String AUTHORIZATION_DENIED = "AUTHORIZATION_DENIED";

  private static final Logger log = LoggerFactory.getLogger(SecurityAuditRecorder.class);
  private static final int MAX_ROUTE = 255;

  private final SecurityAuditRepository repository;
  private final TraceContextAccessor traceContext;

  public SecurityAuditRecorder(
      SecurityAuditRepository repository, TraceContextAccessor traceContext) {
    this.repository = repository;
    this.traceContext = traceContext;
  }

  /**
   * Appends a denial record. Never throws: losing the audit sink must not convert a correct 401/403
   * into a 500, because that would turn an availability problem in the audit path into a change in
   * the security decision the caller observes.
   */
  public void recordDenial(
      String eventType, HttpServletRequest request, int statusCode, String reasonCode) {
    String interactionId = CorrelationContext.interactionId(request);
    if (interactionId.isBlank()) {
      interactionId = CorrelationContext.currentInteractionId();
    }
    if (interactionId.isBlank()) {
      // The filter always assigns one. If it is somehow absent the row would violate its NOT NULL
      // constraint, and failing the request over an audit write is the wrong trade.
      log.warn("Security denial without an interactionId; audit row skipped. event={} status={}",
          eventType, statusCode);
      return;
    }
    try {
      repository.append(eventType, "DENIED", currentSubject(), request.getMethod(), route(request),
          statusCode, reasonCode, null, interactionId, traceContext.traceId());
    } catch (RuntimeException failure) {
      log.error("Failed to append security audit record. event={} interactionId={}",
          eventType, interactionId, failure);
    }
  }

  /** Prefer the mapped route pattern over the raw URI so identifiers do not become audit noise. */
  private static String route(HttpServletRequest request) {
    Object pattern = request.getAttribute(
        "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
    String value = pattern instanceof String string ? string : request.getRequestURI();
    if (value == null) {
      return null;
    }
    return value.length() <= MAX_ROUTE ? value : value.substring(0, MAX_ROUTE);
  }

  private static String currentSubject() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null || !authentication.isAuthenticated()
        ? null
        : authentication.getName();
  }
}
